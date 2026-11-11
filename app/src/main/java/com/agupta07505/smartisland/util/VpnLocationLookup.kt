/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retry orchestration with a live privacy check before every wait and endpoint dispatch. */
internal object VpnLocationLookup {
    suspend fun lookup(
        networkAllowed: () -> Boolean,
        endpoints: List<String>,
        attempts: Int,
        request: suspend (String) -> String?
    ): String? {
        repeat(attempts) { attempt ->
            currentCoroutineContext().ensureActive()
            if (!networkAllowed()) return null
            // The tunnel needs a moment before traffic flows through it.
            delay(if (attempt == 0) 1500L else 2000L)
            for (endpoint in endpoints) {
                currentCoroutineContext().ensureActive()
                if (!networkAllowed()) return null
                val location = request(endpoint)
                currentCoroutineContext().ensureActive()
                if (!networkAllowed()) return null
                if (location != null) return location
            }
        }
        return null
    }
}
