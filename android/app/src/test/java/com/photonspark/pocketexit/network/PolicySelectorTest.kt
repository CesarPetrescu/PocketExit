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
}
