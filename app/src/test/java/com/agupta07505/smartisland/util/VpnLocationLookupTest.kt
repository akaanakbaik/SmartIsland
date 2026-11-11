/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnLocationLookupTest {
    private val endpoints = listOf("primary", "fallback")

    @Test
    fun disabledAccessMakesNoRequest() = runTest {
        val calls = mutableListOf<String>()
        val result = VpnLocationLookup.lookup({ false }, endpoints, 4) { calls += it; null }
        assertNull(result)
        assertTrue(calls.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun disablingDuringTunnelDelayPreventsFirstRequest() = runTest {
        var allowed = true
        val calls = mutableListOf<String>()
        val lookup = async { VpnLocationLookup.lookup({ allowed }, endpoints, 4) { calls += it; null } }
        runCurrent()
        advanceTimeBy(1000)
        allowed = false
        assertNull(lookup.await())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun revocationAfterPrimaryFailurePreventsFallbackAndRetries() = runTest {
        var allowed = true
        val calls = mutableListOf<String>()
        val result = VpnLocationLookup.lookup({ allowed }, endpoints, 4) {
            calls += it
            allowed = false
            null
        }
        assertNull(result)
        assertEquals(listOf("primary"), calls)
    }

    @Test
    fun disablingDuringRetryDelayPreventsAnotherAttempt() = runTest {
        var allowed = true
        val calls = mutableListOf<String>()
        val lookup = async { VpnLocationLookup.lookup({ allowed }, endpoints, 4) { calls += it; null } }
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(endpoints, calls)
        allowed = false
        assertNull(lookup.await())
        assertEquals(endpoints, calls)
    }

    @Test
    fun enabledAccessRetainsFallbackAndRetryBehavior() = runTest {
        val calls = mutableListOf<String>()
        val result = VpnLocationLookup.lookup({ true }, endpoints, 4) {
            calls += it
            if (calls.size == 4) "Berlin, Germany" else null
        }
        assertEquals("Berlin, Germany", result)
        assertEquals(listOf("primary", "fallback", "primary", "fallback"), calls)
        assertEquals(3500L, testScheduler.currentTime)
    }

    @Test
    fun revocationDuringInFlightRequestDiscardsLocation() = runTest {
        var allowed = true
        val response = CompletableDeferred<String?>()
        val calls = mutableListOf<String>()
        val lookup = async {
            VpnLocationLookup.lookup({ allowed }, endpoints, 4) { calls += it; response.await() }
        }
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        allowed = false
        response.complete("Berlin, Germany")
        assertNull(lookup.await())
        assertEquals(listOf("primary"), calls)
    }

    @Test
    fun cancellationDuringRequestPreventsFallback() = runTest {
        val response = CompletableDeferred<String?>()
        val calls = mutableListOf<String>()
        val lookup = async {
            VpnLocationLookup.lookup({ true }, endpoints, 4) { calls += it; response.await() }
        }
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        lookup.cancel()
        response.complete(null)
        lookup.join()
        assertTrue(lookup.isCancelled)
        assertEquals(listOf("primary"), calls)
    }
}
