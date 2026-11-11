/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.agupta07505.smartisland.service.SmartIslandNotificationListenerService
import com.agupta07505.smartisland.service.SmartIslandOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuManager {

    private val KNOWN_PACKAGES = listOf("moe.shizuku.privileged.api")

    /** The package that declares Shizuku's API permission, or null. Any app declaring it counts (official app, fork or renamed build). */
    fun findPackageName(context: Context): String? = runCatching {
        context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0).packageName
    }.getOrNull()

    /**
     * Checks if Shizuku is installed. Tried in order, the first hit wins:
     * 1. the app that declares Shizuku's API permission (any fork or renamed build);
     * 2. a known package name (the official app);
     * 3. a live Shizuku service, which can only exist if something provides it (this also covers Sui and phones where package
     *    visibility hides the app from this one).
     * Crash proof against Throwable (including unit test stubs).
     */
    fun isInstalled(context: Context): Boolean {
        if (findPackageName(context) != null) return true
        val byName = KNOWN_PACKAGES.any { name ->
            try {
                context.packageManager.getPackageInfo(name, 0)
                true
            } catch (t: Throwable) {
                false
            }
        }
        return byName || isBinderAvailable()
    }

    /** Catches any Throwable, including DeadObjectException and RemoteException. */
    fun isBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        if (!isBinderAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        if (!isBinderAvailable()) return
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            android.util.Log.e("ShizukuManager", "Failed to request Shizuku permission", t)
        }
    }

    internal data class CommandOutcome(val command: String, val exitCode: Int)
    internal data class CapabilityCheck(val name: String, val granted: Boolean, val required: Boolean)

    internal fun commandScript(commands: List<String>, marker: String): String =
        commands.mapIndexed { index, command ->
            "$command\n_si_status=$?\nprintf '\\n$marker:$index:%s\\n' \"\$_si_status\""
        }.joinToString("\n")

    internal fun parseCommandOutcomes(commands: List<String>, output: String, marker: String): List<CommandOutcome> {
        val status = Regex("^${Regex.escape(marker)}:(\\d+):(\\d+)$")
        val found = output.lineSequence().mapNotNull { status.matchEntire(it.trim()) }.map {
            it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }.toList()
        require(found.size == commands.size && found.map { it.first } == commands.indices.toList()) {
            "Shizuku setup did not return a result for every command."
        }
        return found.map { (index, code) -> CommandOutcome(commands[index], code) }
    }

    internal fun assessSetup(
        outcomes: List<CommandOutcome>,
        optionalCommands: Set<String>,
        checks: List<CapabilityCheck>
    ): Result<String> {
        val failedRequired = outcomes.filter { it.exitCode != 0 && it.command !in optionalCommands }
        val missingRequired = checks.filter { it.required && !it.granted }
        if (failedRequired.isNotEmpty() || missingRequired.isNotEmpty()) {
            val detail = missingRequired.joinToString { it.name }.ifBlank {
                "${failedRequired.size} required command(s) failed"
            }
            return Result.failure(IllegalStateException("Shizuku setup incomplete: $detail"))
        }
        val optionalFailures = outcomes.count { it.exitCode != 0 && it.command in optionalCommands }
        val missingOptional = checks.filter { !it.required && !it.granted }.joinToString { it.name }
        return Result.success(buildString {
            append(if (checks.isEmpty()) "Setup commands completed." else "Required access verified.")
            if (optionalFailures > 0) append(" $optionalFailures optional command(s) were unsupported or failed.")
            if (missingOptional.isNotEmpty()) append(" Optional access not verified: $missingOptional.")
        })
    }

    internal class SetupErrorDrain(
        private val worker: Thread,
        private val failure: java.util.concurrent.atomic.AtomicReference<Throwable?>
    ) {
        fun await(): Result<Unit> {
            worker.join()
            return failure.get()?.let {
                Result.failure<Unit>(IllegalStateException("Unable to read Shizuku setup error stream.", it))
            } ?: Result.success(Unit)
        }
    }

    internal fun drainSetupErrors(
        stream: () -> java.io.InputStream,
        onFailure: () -> Unit = {}
    ): SetupErrorDrain {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread({
            runCatching {
                stream().use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) { /* Discard raw OEM diagnostics. */ }
                }
            }.exceptionOrNull()?.let {
                failure.set(it)
                // A failed drain must not leave stdout blocked behind a full stderr pipe.
                runCatching { onFailure() }
            }
        }, "ShizukuSetupStderr").apply { isDaemon = true; start() }
        return SetupErrorDrain(worker, failure)
    }

    private fun runShizukuCommands(
        commands: List<String>,
        optionalCommands: Set<String> = emptySet(),
        verify: () -> List<CapabilityCheck> = { emptyList() }
    ): Result<String> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Shizuku permission not granted or service binder offline."))
        }
        return runCatching {
            val marker = "SI_SETUP_" + java.util.UUID.randomUUID().toString().replace("-", "")
            val fullScript = commandScript(commands, marker)
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", fullScript), null, null) as Process
            try {
                // Drain stderr concurrently so an unsupported OEM command cannot fill the pipe.
                val stderr = drainSetupErrors({ process.errorStream }, { process.destroy() })
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                stderr.await().getOrThrow()
                check(exitCode == 0) { "Shizuku setup shell exited with code $exitCode." }
                assessSetup(parseCommandOutcomes(commands, output, marker), optionalCommands, verify()).getOrThrow()
            } finally {
                process.destroy()
            }
        }
    }

    private fun enabledComponent(context: Context, setting: String, component: String): Boolean = runCatching {
        val expected = ComponentName.unflattenFromString(component)
        Settings.Secure.getString(context.contentResolver, setting).orEmpty().split(':').any {
            expected != null && ComponentName.unflattenFromString(it) == expected
        }
    }.getOrDefault(false)

    private fun accessibilityAllowed(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            enabledComponent(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "${context.packageName}/${SmartIslandOverlayService::class.java.name}")
    }.getOrDefault(false)

    private fun notificationListenerAllowed(context: Context): Boolean = runCatching {
        val component = ComponentName(context, SmartIslandNotificationListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.isNotificationListenerAccessGranted(component)
        } else {
            enabledComponent(context, "enabled_notification_listeners", component.flattenToString())
        }
    }.getOrDefault(false)

    private fun appOpAllowed(context: Context, operation: String): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(operation, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun isBackgroundProtectionAllowed(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName) &&
            appOpAllowed(context, "android:run_in_background") &&
            appOpAllowed(context, "android:run_any_in_background")
    }.getOrDefault(false)

    private fun setupChecks(context: Context, scope: SetupScope): List<CapabilityCheck> = buildList {
        add(CapabilityCheck("overlay", Settings.canDrawOverlays(context), true))
        add(CapabilityCheck("accessibility", accessibilityAllowed(context), true))
        add(CapabilityCheck("notification listener", notificationListenerAllowed(context), true))
        add(CapabilityCheck("write system settings", Settings.System.canWrite(context), false))
        add(CapabilityCheck("background app-ops and battery exemption", isBackgroundProtectionAllowed(context), false))
        if (android.os.Build.VERSION.SDK_INT >= 31) add(CapabilityCheck("Bluetooth connect",
            context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED, false))
        if (android.os.Build.VERSION.SDK_INT >= 33) add(CapabilityCheck("post notifications",
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED, false))
        if (android.os.Build.VERSION.SDK_INT >= 35) add(CapabilityCheck("sensitive notifications",
            isSensitiveNotificationAccessAllowed(context), false))
        if (scope == SetupScope.All) {
            add(CapabilityCheck("usage access", appOpAllowed(context, "android:get_usage_stats"), false))
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            add(CapabilityCheck("Do Not Disturb access", manager.isNotificationPolicyAccessGranted, false))
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION").forEach {
                add(CapabilityCheck(it.substringAfterLast('.'), context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED, false))
            }
        }
    }

    /**
     * Runs one shell command through Shizuku and returns its stdout. For read-only dumps such as `dumpsys`; fails
     * when Shizuku is not running or not permitted.
     */
    suspend fun runShellForOutput(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext Result.failure(IllegalStateException("Shizuku permission not granted or service binder offline."))
        }
        runCatching {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output
        }
    }

    /**
     * Starts one shell command through Shizuku and returns the running process, so its output can be read line by
     * line while it keeps running (a `logcat` follow).
     */
    fun openShellStream(command: String): Process? {
        if (!hasPermission()) return null
        return runCatching {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        }.getOrNull()
    }

    /**
     * Runs one shell command through Shizuku and returns its raw stdout bytes (at most [maxBytes], else null).
     * Blocking. Used to read a picture file the app itself may not open.
     */
    fun runShellForBytes(command: String, maxBytes: Int): ByteArray? {
        if (!hasPermission()) return null
        return runCatching {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = java.io.ByteArrayOutputStream()
            var tooBig = false
            process.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) { tooBig = true; break }
                }
            }
            if (tooBig) process.destroy() else process.waitFor()
            if (tooBig) null else out.toByteArray()
        }.getOrNull()
    }

    internal fun mergeColonSeparated(currentList: String, newEntry: String): String {
        val list = currentList.split(':').filter { it.isNotBlank() }.toMutableList()
        if (!list.contains(newEntry)) {
            list.add(newEntry)
        }
        return list.joinToString(":")
    }

    internal fun getMergedAccessibilityServices(context: Context, serviceComponent: String): String {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return mergeColonSeparated(current, serviceComponent)
    }

    internal fun getMergedNotificationListeners(context: Context, listenerComponent: String): String {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        return mergeColonSeparated(current, listenerComponent)
    }

    /** How much the 1-tap setup grants: only what the island needs to work, or that plus every optional permission. */
    enum class SetupScope { Necessary, All }

    private const val SETUP_PREFS = "shizuku_setup"
    private const val SETUP_SCOPE_KEY = "scope"

    fun savedScope(context: Context): SetupScope? = runCatching {
        val name = context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).getString(SETUP_SCOPE_KEY, null)
        SetupScope.entries.firstOrNull { it.name == name }
    }.getOrNull()

    private fun saveScope(context: Context, scope: SetupScope) {
        runCatching {
            context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).edit().putString(SETUP_SCOPE_KEY, scope.name).apply()
        }
    }

    /**
     * The shell commands of one setup. Necessary: everything the island needs to work, and the steps that take many
     * screens by hand.
     */
    internal fun setupCommands(
        scope: SetupScope,
        pkg: String,
        accessibilityClass: String,
        notificationClass: String,
        mergedAccessibilityServices: String,
        mergedNotificationListeners: String
    ): List<String> {
        val necessary = listOf(
            // Accessibility and notification access cannot be switched on while this is still restricted.
            "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            // Modify system settings (the brightness slider on the Utilities page).
            "appops set $pkg WRITE_SETTINGS allow",
            "appops set $pkg BIND_ACCESSIBILITY_SERVICE allow",
            "appops set $pkg POST_NOTIFICATION allow",
            "appops set $pkg AUTO_START allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "settings put secure enabled_accessibility_services $mergedAccessibilityServices",
            "settings put secure accessibility_enabled 1",
            "cmd notification allow_listener $notificationClass",
            "settings put secure enabled_notification_listeners $mergedNotificationListeners",
            "am set-standby-bucket $pkg active",
            "dumpsys deviceidle whitelist +$pkg"
        ) + runtimePermissionCommands(pkg) + sensitiveNotificationCommands(pkg)
        return if (scope == SetupScope.All) necessary + optionalCommands(pkg) else necessary
    }

    /**
     * Permissions only some features use, granted by the "all permissions" setup and never by the necessary one:
     * - Usage access (recent apps in the App Shortcuts)
     * - Do Not Disturb access (the silent ringer, the mode idle widget)
     * - Location, fine and coarse (the weather widget, the Wi-Fi network name)
     * Unsupported commands are recorded as optional failures; the remaining commands still run.
     * The VPN consent is a system dialog that cannot be granted from here.
     */
    internal fun optionalCommands(pkg: String): List<String> = listOf(
        "appops set $pkg GET_USAGE_STATS allow",
        "cmd notification allow_dnd $pkg",
        "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
        "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION"
    )

    /**
     * Repairs a stale or crashed AccessibilityService binding by switching Accessibility off and on, keeping every
     * other enabled service.
     */
    suspend fun repairAccessibility(context: Context): Result<String> =
        withContext(Dispatchers.IO) {
            val pkg = context.packageName
            val accessibilityClass =
                "$pkg/${SmartIslandOverlayService::class.java.name}"

            val shortAccessibilityClass =
                ComponentName(
                    context,
                    SmartIslandOverlayService::class.java
                ).flattenToShortString()

            val commands = listOf(
                "current=\$(settings get secure enabled_accessibility_services)",

                "settings put secure accessibility_enabled 0",

                // Remove Smart Island in either representation, keeping every other enabled service.
                "new=''; IFS=':'; for s in \$current; do if [ -n \"\$s\" ] && [ \"\$s\" != \"$accessibilityClass\" ] && [ \"\$s\" != \"$shortAccessibilityClass\" ]; then if [ -n \"\$new\" ]; then new=\"\$new:\$s\"; else new=\"\$s\"; fi; fi; done; if [ -n \"\$new\" ] && [ \"\$new\" != \"null\" ]; then settings put secure enabled_accessibility_services \"\$new\"; else settings delete secure enabled_accessibility_services; fi",

                // Give Android/HyperOS time to process the disable.
                "sleep 2",

                "current=\$(settings get secure enabled_accessibility_services); if [ -n \"\$current\" ] && [ \"\$current\" != \"null\" ]; then current=\"\$current:$shortAccessibilityClass\"; else current=\"$shortAccessibilityClass\"; fi; settings put secure enabled_accessibility_services \"\$current\"",

                "settings put secure accessibility_enabled 1"
            )

            runShizukuCommands(commands, verify = {
                listOf(CapabilityCheck("accessibility", accessibilityAllowed(context), true))
            })
        }

    suspend fun autoGrantNecessaryPermissions(context: Context): Result<String> = grant(context, SetupScope.Necessary)

    suspend fun autoGrantAllPermissions(context: Context): Result<String> = grant(context, SetupScope.All)

    /** Applies again the scope the user chose last (the necessary one when none was chosen yet). */
    suspend fun reapplySavedSetup(context: Context): Result<String> = grant(context, savedScope(context) ?: SetupScope.Necessary)

    private suspend fun grant(context: Context, scope: SetupScope): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val accessibilityClass = "$pkg/${SmartIslandOverlayService::class.java.name}"
        val notificationClass = "$pkg/${SmartIslandNotificationListenerService::class.java.name}"
        val commands = setupCommands(
            scope = scope,
            pkg = pkg,
            accessibilityClass = accessibilityClass,
            notificationClass = notificationClass,
            mergedAccessibilityServices = getMergedAccessibilityServices(context, accessibilityClass),
            mergedNotificationListeners = getMergedNotificationListeners(context, notificationClass)
        )
        // Different ROMs support different routes to the same grant. Verify core effective state
        // after all attempts, while reporting optional OEM/feature failures instead of aborting them.
        runShizukuCommands(commands, commands.toSet()) { setupChecks(context, scope) }
            .onSuccess { saveScope(context, scope) }
    }

    /**
     * Runtime permissions of the necessary setup: nearby devices (Bluetooth connect, for the Bluetooth cards) and
     * notifications.
     */
    internal fun runtimePermissionCommands(pkg: String): List<String> = listOf(
        "pm grant $pkg android.permission.BLUETOOTH_CONNECT",
        "pm grant $pkg android.permission.POST_NOTIFICATIONS"
    )

    /** Permissions the necessary setup never grants; only the "all permissions" setup does (see [optionalCommands]). */
    internal val OPTIONAL_PERMISSIONS = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION"
    )

    /**
     * Shell commands that let this app read notifications Android marked as sensitive. Without them the system hands
     * the listener "Sensitive notification content hidden" instead of the title and text.
     */
    internal fun sensitiveNotificationCommands(pkg: String): List<String> = listOf(
        "pm grant $pkg android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS",
        "appops set $pkg RECEIVE_SENSITIVE_NOTIFICATIONS allow"
    )

    /**
     * True when this app may read notifications Android marked as sensitive: either the permission or the app op of
     * the same name is granted (the two commands of [sensitiveNotificationCommands] set one each; a ROM may honour
     * only one of them).
     */
    fun isSensitiveNotificationAccessAllowed(context: Context): Boolean {
        val byPermission = context.checkSelfPermission("android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED
        if (byPermission) return true
        // The string form of the check needs Android 10; an unknown operation name throws, which is simply "not allowed" here.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        return runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            ops.unsafeCheckOpNoThrow(
                "android:receive_sensitive_notifications",
                android.os.Process.myUid(),
                context.packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    suspend fun grantSensitiveNotificationAccess(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val commands = sensitiveNotificationCommands(context.packageName)
        runShizukuCommands(commands, commands.toSet()) {
            listOf(CapabilityCheck("sensitive notifications", isSensitiveNotificationAccessAllowed(context), true))
        }
    }

    /**
     * Shell script that force-stops this app and starts it again. It runs inside Shizuku's shell, not in this
     * process, so it keeps going after the force-stop kills the app.
     */
    internal fun restartCommands(
        pkg: String,
        launcherActivity: String,
        accessibilityComponent: String,
        notificationComponent: String,
        accessibilityWasEnabled: Boolean,
        notificationWasEnabled: Boolean,
        mergedAccessibilityServices: String
    ): List<String> = buildList {
        add("am force-stop $pkg")
        if (accessibilityWasEnabled) {
            add("settings put secure enabled_accessibility_services $mergedAccessibilityServices")
            add("settings put secure accessibility_enabled 1")
        }
        if (notificationWasEnabled) add("cmd notification allow_listener $notificationComponent")
        add("sleep 1")
        add("am start -n $pkg/$launcherActivity")
    }

    /**
     * Grants the sensitive-notification access, then force-stops and relaunches the app so the notification listener
     * picks the new access up without the user restarting anything by hand.
     */
    suspend fun grantSensitiveNotificationAccessAndRestart(
        context: Context,
        onGranted: suspend () -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val granted = grantSensitiveNotificationAccess(context)
        if (granted.isFailure) return@withContext granted

        val accessibilityComponent = "$pkg/${SmartIslandOverlayService::class.java.name}"
        val notificationComponent = "$pkg/${SmartIslandNotificationListenerService::class.java.name}"
        val enabledAccessibility = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val enabledListeners = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        val script = restartCommands(
            pkg = pkg,
            launcherActivity = com.agupta07505.smartisland.MainActivity::class.java.name,
            accessibilityComponent = accessibilityComponent,
            notificationComponent = notificationComponent,
            accessibilityWasEnabled = enabledAccessibility.split(':').contains(accessibilityComponent),
            notificationWasEnabled = enabledListeners.split(':').contains(notificationComponent),
            mergedAccessibilityServices = mergeColonSeparated(enabledAccessibility, accessibilityComponent)
        )
        onGranted()
        runShizukuCommands(script)
    }

    /** Grants OEM Autostart and disables background kill and app standby restrictions. */
    suspend fun grantOemAutostartAndKillProtection(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val commands = listOf(
            "appops set $pkg AUTO_START allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "am set-standby-bucket $pkg active",
            "dumpsys deviceidle whitelist +$pkg"
        )
        runShizukuCommands(commands, commands.toSet()) {
            listOf(CapabilityCheck("background app-ops and battery exemption", isBackgroundProtectionAllowed(context), true))
        }
    }
}
