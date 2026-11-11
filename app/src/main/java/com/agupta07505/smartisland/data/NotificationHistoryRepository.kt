/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Storage operations share the repository's lock, including reads and policy changes. */
internal interface NotificationHistoryStore {
    suspend fun entries(): List<NotificationHistoryEntry>
    suspend fun insert(entry: NotificationHistoryEntry): Long
    suspend fun markOpened(key: String)
    suspend fun delete(id: Long)
    suspend fun deletePackage(packageName: String): Int
    suspend fun clear()
    suspend fun search(query: String): List<NotificationHistoryEntry>
    suspend fun deleteBefore(cutoff: Long)
}

private class SQLiteHistoryStore(context: Context) : NotificationHistoryStore {
    private val db = NotificationHistoryDbHelper(context.applicationContext)
    override suspend fun entries() = db.getAllEntries()
    override suspend fun insert(entry: NotificationHistoryEntry) = db.insertEntry(entry)
    override suspend fun markOpened(key: String) { db.markAsOpened(key) }
    override suspend fun delete(id: Long) { db.deleteById(id) }
    override suspend fun deletePackage(packageName: String) = db.deleteByPackage(packageName)
    override suspend fun clear() { db.deleteAll() }
    override suspend fun search(query: String) = db.searchEntries(query)
    override suspend fun deleteBefore(cutoff: Long) { db.deleteOlderThan(cutoff) }
}

@Singleton
class NotificationHistoryRepository internal constructor(
    private val store: NotificationHistoryStore,
    private val retentionHours: Flow<Int>,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : INotificationHistoryRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SmartIslandSettingsRepository
    ) : this(
        SQLiteHistoryStore(context),
        settingsRepository.settings.map { it.notificationHistoryRetentionHours },
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("NotificationHistoryRepo", "Unhandled repository coroutine failure", throwable)
        })
    )

    constructor(context: Context) : this(context, SmartIslandSettingsRepository(context.applicationContext))

    private val mutex = Mutex()
    private val _history = MutableStateFlow<List<NotificationHistoryEntry>>(emptyList())
    override val history: StateFlow<List<NotificationHistoryEntry>> = _history.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            // The first persisted policy is read before any rows are published. Restores/edits also
            // prune even when capture is disabled and no notification arrives.
            retentionHours.distinctUntilChanged().collect {
                reload()
            }
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock { block() }
    }

    /** Called only while holding mutex. The persisted policy, not a stale listener snapshot, wins. */
    private suspend fun prune(): Long? {
        val hours = retentionHours.first()
        if (hours <= 0) return null // Preserve the existing 'keep indefinitely' policy.
        val cutoff = nowMillis() - hours.toLong() * 3_600_000L
        store.deleteBefore(cutoff)
        _history.value = _history.value.filter { it.postTimeMillis >= cutoff }
        return cutoff
    }

    override suspend fun reload() = serialized {
        val cutoff = prune()
        // Filter as well as delete: a failed SQLite delete must not expose expired entries.
        _history.value = store.entries().filter { cutoff == null || it.postTimeMillis >= cutoff }
    }

    override suspend fun saveEntry(entry: NotificationHistoryEntry) = serialized<Unit> {
        val cutoff = prune()
        if (cutoff == null || entry.postTimeMillis >= cutoff) {
            val id = store.insert(entry)
            if (id > 0) _history.value = (listOf(entry.copy(id = id)) + _history.value).take(1000)
        }
    }

    override suspend fun markAsOpened(notificationKey: String) = serialized {
        prune()
        store.markOpened(notificationKey)
        _history.value = _history.value.map {
            if (it.notificationKey == notificationKey) it.copy(wasOpened = true) else it
        }
    }

    override suspend fun deleteEntry(id: Long) = serialized {
        store.delete(id)
        _history.value = _history.value.filter { it.id != id }
    }

    override suspend fun deleteByPackage(packageName: String): Int = serialized {
        val count = store.deletePackage(packageName)
        if (count > 0) _history.value = _history.value.filter { it.packageName != packageName }
        count
    }

    override suspend fun clearAll() = serialized {
        store.clear()
        _history.value = emptyList()
    }

    override suspend fun search(query: String): List<NotificationHistoryEntry> = serialized {
        val cutoff = prune()
        val entries = if (query.isBlank()) _history.value else store.search(query.trim())
        entries.filter { cutoff == null || it.postTimeMillis >= cutoff }
    }

    override suspend fun cleanupOldEntries(retentionHours: Int) = serialized {
        // Retain the interface for callers, but a stale caller cannot extend the persisted policy.
        prune()
        Unit
    }
}
