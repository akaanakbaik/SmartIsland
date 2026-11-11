/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWeatherCitySearchTest {
    @Test
    fun disabledNetworkNeverInvokesHttpTransport() = runTest {
        var calls = 0
        val result = IdleWeather.searchCities("Berlin", { false }) {
            calls++
            "{}"
        }
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun enabledSearchUsesTransportAndParsesItsResponse() = runTest {
        var calls = 0
        val result = IdleWeather.searchCities("Berlin", { true }) {
            calls++
            """{"results":[{"name":"Berlin","latitude":52.52,"longitude":13.40}]}"""
        }
        assertEquals(1, calls)
        assertEquals("Berlin", result.single().name)
        assertEquals(52.5f, result.single().lat, 0.001f)
    }
}
