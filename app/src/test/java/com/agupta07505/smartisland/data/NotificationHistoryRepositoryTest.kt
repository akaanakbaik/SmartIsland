/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package com.agupta07505.smartisland.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationHistoryRepositoryTest {
    private val hour = 3_600_000L
    private fun entry(key: String, at: Long, id: Long = 0) = NotificationHistoryEntry(
        id = id, notificationKey = key, packageName = "example.app", appName = "Example",
        title = key, text = "private text", postTimeMillis = at
    )

    private class Store : NotificationHistoryStore {
        val rows = mutableListOf<NotificationHistoryEntry>()
        var nextId = 100L
        var beforeInsert: suspend () -> Unit = {}
        var beforeEntriesReturn: suspend () -> Unit = {}
        var ignoreDeletion = false
        var insertFails = false
        var packageDeleteFails = false
        override suspend fun entries(): List<NotificationHistoryEntry> {
            val snapshot = rows.sortedByDescending { it.postTimeMillis }.take(1000)
            beforeEntriesReturn()
            return snapshot
        }
        override suspend fun insert(entry: NotificationHistoryEntry): Long {
            beforeInsert()
            if (insertFails) return -1
            val id = nextId++
            rows.add(entry.copy(id = id))
            return id
        }
        override suspend fun markOpened(key: String) {
            rows.replaceAll { if (it.notificationKey == key) it.copy(wasOpened = true) else it }
        }
        override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deletePackage(packageName: String): Int {
            if (packageDeleteFails) return 0
            val count = rows.count { it.packageName == packageName }
            rows.removeAll { it.packageName == packageName }
            return count
        }
        override suspend fun clear() { rows.clear() }
        override suspend fun search(query: String) = rows.filter { it.title.contains(query) }
        override suspend fun deleteBefore(cutoff: Long) {
            if (!ignoreDeletion) rows.removeAll { it.postTimeMillis < cutoff }
        }
    }

    @Test
    fun startupPrunesUsingPersistedPolicyBeforePublishingAnyRows() = runTest {
        val store = Store().apply { rows.addAll(listOf(entry("old", 0, 1), entry("fresh", 99 * hour, 2))) }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        assertTrue(repository.history.value.isEmpty())
        runCurrent()
        assertEquals(listOf("fresh"), repository.history.value.map { it.title })
        assertEquals(listOf("fresh"), store.rows.map { it.title })
    }

    @Test
    fun searchAndReloadExpireWithoutAnyIncomingNotification() = runTest {
        var now = 100 * hour
        val store = Store().apply { rows.add(entry("expires", now, 1)) }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { now }
        runCurrent()
        now += 25 * hour
        assertTrue(repository.search("expires").isEmpty())
        assertTrue(repository.history.value.isEmpty())
        assertTrue(store.rows.isEmpty())
        repository.reload()
        assertTrue(repository.history.value.isEmpty())
    }

    @Test
    fun retentionEditPrunesEvenWhenNoNewEntryIsSaved() = runTest {
        val policy = MutableStateFlow(72)
        val store = Store().apply { rows.add(entry("two days old", 52 * hour, 1)) }
        val repository = NotificationHistoryRepository(store, policy, backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        assertEquals(1, repository.history.value.size)
        policy.value = 24
        runCurrent()
        assertTrue(repository.history.value.isEmpty())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun failedDatabasePruneStillCannotExposeExpiredRowsThroughReloadOrSearch() = runTest {
        val store = Store().apply { ignoreDeletion = true; rows.add(entry("old", 0, 1)) }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        assertTrue(repository.history.value.isEmpty())
        assertTrue(repository.search("old").isEmpty())
        assertEquals(1, store.rows.size)
    }

    @Test
    fun insertAndClearAreSerializedAcrossDatabaseAndCache() = runTest {
        val store = Store()
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeInsert = { entered.complete(Unit); release.await() }
        val save = launch { repository.saveEntry(entry("new", 100 * hour)) }
        runCurrent()
        entered.await()
        val clear = launch { repository.clearAll() }
        runCurrent()
        assertFalse(clear.isCompleted)
        release.complete(Unit)
        save.join(); clear.join()
        assertTrue(store.rows.isEmpty())
        assertTrue(repository.history.value.isEmpty())
    }

    @Test
    fun concurrentSavesKeepBothDatabaseRowsAndCachedEntries() = runTest {
        val store = Store()
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        store.beforeInsert = { if (calls++ == 0) { entered.complete(Unit); release.await() } }
        val first = launch { repository.saveEntry(entry("first", 100 * hour)) }
        runCurrent()
        entered.await()
        val second = launch { repository.saveEntry(entry("second", 100 * hour)) }
        runCurrent()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.join(); second.join()
        assertEquals(setOf("first", "second"), repository.history.value.map { it.title }.toSet())
        assertEquals(2, store.rows.size)
    }

    @Test
    fun reloadCannotRestoreDeletedRowsFromAnEarlierSnapshot() = runTest {
        val store = Store().apply { rows.add(entry("present", 100 * hour, 1)) }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeEntriesReturn = { captured.complete(Unit); release.await() }
        val reload = launch { repository.reload() }
        runCurrent()
        captured.await()
        val clear = launch { repository.clearAll() }
        runCurrent()
        assertFalse(clear.isCompleted)
        release.complete(Unit)
        reload.join(); clear.join()
        assertTrue(store.rows.isEmpty())
        assertTrue(repository.history.value.isEmpty())
    }

    @Test
    fun failedInsertDoesNotCreatePhantomHistoryEntry() = runTest {
        val store = Store().apply { insertFails = true }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        repository.saveEntry(entry("new", 100 * hour))
        assertTrue(repository.history.value.isEmpty())
    }

    @Test
    fun failedPackageDeletionDoesNotHideRowsStillPresentInDatabase() = runTest {
        val store = Store().apply {
            packageDeleteFails = true
            rows.add(entry("retained", 100 * hour, 1))
        }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        assertEquals(0, repository.deleteByPackage("example.app"))
        assertEquals(listOf("retained"), repository.history.value.map { it.title })
        assertEquals(listOf("retained"), store.rows.map { it.title })
    }

    @Test
    fun staleCallerCannotExtendCurrentRetention() = runTest {
        val store = Store().apply { rows.add(entry("old", 52 * hour, 1)) }
        val repository = NotificationHistoryRepository(store, MutableStateFlow(24), backgroundScope,
            StandardTestDispatcher(testScheduler)) { 100 * hour }
        runCurrent()
        repository.cleanupOldEntries(720)
        assertTrue(repository.history.value.isEmpty())
        assertTrue(store.rows.isEmpty())
    }
}
