package com.photonspark.pocketexit.network

import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.Policy
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicySelectorTest {
    @Test
    fun cellularStrictNeverFallsBackToWifi() {
        val state = NetworkAvailability(
            wifiAvailable = true,
            wifiValidated = true,
            cellularAvailable = false,
            cellularValidated = false,
        )
        assertEquals(NetworkKind.NONE, PolicySelector.select(Policy.CELLULAR_ONLY, state))
    }

    @Test
    fun cellularPreferredFallsBackToWifi() {
        val state = NetworkAvailability(
            wifiAvailable = true,
            wifiValidated = true,
            cellularAvailable = true,
            cellularValidated = false,
        )
        assertEquals(NetworkKind.WIFI, PolicySelector.select(Policy.CELLULAR_PREFERRED, state))
    }

    @Test
    fun autoPrefersValidatedWifi() {
        val state = NetworkAvailability(true, true, true, true)
        assertEquals(NetworkKind.WIFI, PolicySelector.select(Policy.AUTO, state))
    }

    @Test
    fun preferredRouteTracksValidationChanges() {
        val wifiOnly = NetworkAvailability(true, true, false, false)
        val both = NetworkAvailability(true, true, true, true)
        val cellularOnly = NetworkAvailability(false, false, true, true)
        assertEquals(NetworkKind.WIFI, PolicySelector.select(Policy.CELLULAR_PREFERRED, wifiOnly))
        assertEquals(NetworkKind.CELLULAR, PolicySelector.select(Policy.CELLULAR_PREFERRED, both))
        assertEquals(NetworkKind.CELLULAR, PolicySelector.select(Policy.CELLULAR_PREFERRED, cellularOnly))
        assertEquals(NetworkKind.WIFI, PolicySelector.select(Policy.WIFI_PREFERRED, both))
        assertEquals(NetworkKind.CELLULAR, PolicySelector.select(Policy.WIFI_PREFERRED, cellularOnly))
    }
}
