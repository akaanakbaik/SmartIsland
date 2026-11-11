/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import com.agupta07505.smartisland.model.CityResult
import com.agupta07505.smartisland.model.WeatherConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Checks the current privacy choice at dispatch and discards responses after access is revoked. */
internal object CitySearch {
    suspend fun search(
        query: String,
        networkAllowed: () -> Boolean,
        request: suspend (String) -> List<CityResult>
    ): List<CityResult> {
        currentCoroutineContext().ensureActive()
        val city = query.trim()
        if (!networkAllowed() || city.length < WeatherConfig.MIN_SEARCH_LENGTH) return emptyList()
        val results = request(city)
        currentCoroutineContext().ensureActive()
        return if (networkAllowed()) results else emptyList()
    }
}
