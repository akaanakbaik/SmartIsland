/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package com.agupta07505.smartisland.util

import org.junit.Assert.*
import org.junit.Test

class ShizukuSetupResultTest {
    @Test
    fun nonzeroRequiredExitCannotBeHiddenByOutputOrLaterSuccessfulCommand() {
        val commands = listOf("required", "later")
        val outcomes = ShizukuManager.parseCommandOutcomes(
            commands, "some stdout\nAUDIT:0:1\nAUDIT:1:0\n", "AUDIT"
        )
        assertTrue(ShizukuManager.assessSetup(outcomes, emptySet(), emptyList()).isFailure)
    }

    @Test
    fun silentNonzeroExitIsFailure() {
        val outcomes = ShizukuManager.parseCommandOutcomes(listOf("required"), "AUDIT:0:2\n", "AUDIT")
        assertTrue(ShizukuManager.assessSetup(outcomes, emptySet(), emptyList()).isFailure)
    }

    @Test
    fun unsupportedOemStepDoesNotPreventVerifiedCoreAccessButIsReported() {
        val outcomes = listOf(ShizukuManager.CommandOutcome("AUTO_START", 1))
        val checks = listOf(ShizukuManager.CapabilityCheck("overlay", true, true))
        val result = ShizukuManager.assessSetup(outcomes, setOf("AUTO_START"), checks)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("optional command(s)"))
    }

    @Test
    fun zeroExitsDoNotOverrideMissingEffectivePermission() {
        val result = ShizukuManager.assessSetup(
            listOf(ShizukuManager.CommandOutcome("settings put", 0)), emptySet(),
            listOf(ShizukuManager.CapabilityCheck("accessibility", false, true))
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("accessibility"))
    }

    @Test
    fun alternativeGrantRouteCanSucceedWhenOneRomCommandFails() {
        val result = ShizukuManager.assessSetup(
            listOf(ShizukuManager.CommandOutcome("pm grant", 1), ShizukuManager.CommandOutcome("appops set", 0)),
            setOf("pm grant", "appops set"),
            listOf(ShizukuManager.CapabilityCheck("sensitive notifications", true, true))
        )
        assertTrue(result.isSuccess)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedShellOutputCannotReportSuccess() {
        ShizukuManager.parseCommandOutcomes(listOf("first", "second"), "AUDIT:1:0\n", "AUDIT")
    }

    @Test
    fun optionalMissingEffectiveStateIsReportedEvenWithZeroExit() {
        val result = ShizukuManager.assessSetup(
            listOf(ShizukuManager.CommandOutcome("appops set", 0)), setOf("appops set"),
            listOf(ShizukuManager.CapabilityCheck("usage access", false, false))
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("usage access"))
    }

    @Test
    fun failingStderrGetterIsReportedWithoutEscapingWorker() {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val drain = ShizukuManager.drainSetupErrors(
            { throw java.io.IOException("binder unavailable") },
            { stopped.set(true) }
        )
        val result = drain.await()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.cause is java.io.IOException)
        assertTrue(stopped.get())
    }

    @Test
    fun failingStderrReadIsReportedWithoutEscapingWorker() {
        val drain = ShizukuManager.drainSetupErrors({ object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("stream closed")
        } })
        assertTrue(drain.await().isFailure)
    }

    @Test
    fun stderrDrainFailureCallbackCannotEscapeWorker() {
        val drain = ShizukuManager.drainSetupErrors(
            { throw java.io.IOException("stream unavailable") },
            { throw IllegalStateException("destroy unavailable") }
        )
        assertTrue(drain.await().isFailure)
    }

    @Test
    fun scriptPreservesIndividualExitStatusInSharedShell() {
        val commands = listOf("value=kept", "false", "printf '%s' \"\$value\"")
        val script = ShizukuManager.commandScript(commands, "AUDIT")
        // Executes only these audit-authored local commands, never Shizuku or application code.
        val process = ProcessBuilder("sh", "-c", script).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor())
        assertTrue(output.contains("kept"))
        assertEquals(listOf(0, 1, 0), ShizukuManager.parseCommandOutcomes(commands, output, "AUDIT").map { it.exitCode })
    }
}
