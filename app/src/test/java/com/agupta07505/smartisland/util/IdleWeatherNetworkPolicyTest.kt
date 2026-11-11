/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.agupta07505.smartisland.model.WeatherConfig
import com.agupta07505.smartisland.model.WeatherPrefs
import com.agupta07505.smartisland.model.WeatherSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWeatherNetworkPolicyTest {
    private val forecast = """{"current":{"temperature_2m":21.2,"weather_code":3,"is_day":1}}"""
    private val ipPlace = """{"success":true,"latitude":52.52,"longitude":13.40}"""
    private val manual = WeatherPrefs(
        source = WeatherConfig.SOURCE_MANUAL, manualCity = "Berlin", manualLat = 52.5f,
        manualLon = 13.4f, readouts = "AQI"
    )

    private fun storage(): Pair<Context, SharedPreferences> {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        return context to preferences
    }

    @Test
    fun initiallyOfflineSkipsLocationAndAllHttpRequests() = runTest {
        val context = mockk<Context>()
        val calls = mutableListOf<String>()
        assertNull(IdleWeather.fetch(context, manual, { false }) { calls += it; forecast })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun revocationDuringIpLookupPreventsForecastAndLocationCacheWrite() = runTest {
        val (context, preferences) = storage()
        var allowed = true
        val calls = mutableListOf<String>()
        val result = IdleWeather.fetch(context, WeatherPrefs(), { allowed }) {
            calls += it
            allowed = false
            ipPlace
        }
        assertNull(result)
        assertEquals(listOf("https://ipwho.is/"), calls)
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun revocationDuringForecastPreventsAirQualityRequestAndCacheWrite() = runTest {
        val (context, preferences) = storage()
        var allowed = true
        val calls = mutableListOf<String>()
        val result = IdleWeather.fetch(context, manual, { allowed }) {
            calls += it
            allowed = false
            forecast
        }
        assertNull(result)
        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith(WeatherConfig.OPEN_METEO_FORECAST))
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun revocationDuringAirQualityDiscardsResponseAndCacheWrite() = runTest {
        val (context, preferences) = storage()
        var allowed = true
        val calls = mutableListOf<String>()
        val result = IdleWeather.fetch(context, manual, { allowed }) {
            calls += it
            if (it.startsWith(WeatherConfig.OPEN_METEO_AIR_QUALITY)) {
                allowed = false
                """{"current":{"us_aqi":17}}"""
            } else forecast
        }
        assertNull(result)
        assertEquals(2, calls.size)
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun enabledForecastStillLoadsOptionalAirQuality() = runTest {
        val (context, _) = storage()
        val calls = mutableListOf<String>()
        val result = IdleWeather.fetch(context, manual, { true }) {
            calls += it
            if (it.startsWith(WeatherConfig.OPEN_METEO_AIR_QUALITY)) """{"current":{"us_aqi":17}}""" else forecast
        }
        assertNotNull(result)
        assertEquals(2, calls.size)
        assertEquals(17, result!!.extras!!.aqi)
    }

    @Test
    fun customProviderDoesNotDispatchOpenMeteoAirQuality() = runTest {
        val (context, _) = storage()
        val calls = mutableListOf<String>()
        val custom = manual.copy(provider = WeatherConfig.PROVIDER_CUSTOM, customUrl = "https://weather.example/forecast")
        assertNotNull(IdleWeather.fetch(context, custom, { true }) { calls += it; forecast })
        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith(custom.customUrl))
    }

    @Test
    fun cancellationDuringIpLookupPreventsLaterDispatches() = runTest {
        val (context, preferences) = storage()
        val calls = mutableListOf<String>()
        lateinit var lookup: Deferred<WeatherSnapshot?>
        lookup = async {
            IdleWeather.fetch(context, WeatherPrefs(), { true }) {
                calls += it
                lookup.cancel()
                ipPlace
            }
        }
        lookup.join()
        assertTrue(lookup.isCancelled)
        assertEquals(listOf("https://ipwho.is/"), calls)
        verify(exactly = 0) { preferences.edit() }
    }
}
