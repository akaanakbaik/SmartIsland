/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import com.agupta07505.smartisland.model.CityResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CitySearchTest {
    private val city = CityResult("Berlin", "Berlin", "Germany", 52.5f, 13.4f)

    @Test
    fun offlineChoicePreventsTransportDispatch() = runTest {
        var calls = 0
        val result = CitySearch.search("Berlin", { false }) {
            calls++
            listOf(city)
        }
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun onlineSearchPassesTrimmedQueryToTransport() = runTest {
        var sentQuery: String? = null
        val result = CitySearch.search("  Berlin  ", { true }) {
            sentQuery = it
            listOf(city)
        }
        assertEquals("Berlin", sentQuery)
        assertEquals(listOf(city), result)
    }

    @Test
    fun disablingNetworkBeforeQueuedSearchStartsPreventsDispatch() = runTest {
        var allowed = true
        var calls = 0
        val search = async {
            CitySearch.search("Berlin", { allowed }) { calls++; listOf(city) }
        }
        allowed = false
        assertTrue(search.await().isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun responseIsDiscardedIfNetworkAccessWasRevoked() = runTest {
        var allowed = true
        val response = CompletableDeferred<List<CityResult>>()
        val search = async { CitySearch.search("Berlin", { allowed }) { response.await() } }
        runCurrent()
        allowed = false
        response.complete(listOf(city))
        assertTrue(search.await().isEmpty())
    }

    @Test
    fun cancellingPendingSearchDoesNotPublishAResponse() = runTest {
        val response = CompletableDeferred<List<CityResult>>()
        val search = async { CitySearch.search("Berlin", { true }) { response.await() } }
        runCurrent()
        search.cancel()
        response.complete(listOf(city))
        search.join()
        assertTrue(search.isCancelled)
    }
}
