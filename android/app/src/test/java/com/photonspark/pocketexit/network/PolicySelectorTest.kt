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
    fun controlReachesALanServerOverWifiTheSystemNeverValidates() {
        // A laptop on a phone hotspot, a LAN-only router, or a captive network:
        // Wi-Fi is up and claims Internet, and the system will never validate it
        // because there is nothing behind it to validate.
        val unvalidatedWifi = NetworkAvailability(
            wifiAvailable = true,
            wifiValidated = false,
            cellularAvailable = true,
            cellularValidated = true,
        )
        assertEquals(
            NetworkKind.WIFI,
            PolicySelector.select(
                Policy.AUTO,
                unvalidatedWifi,
                PolicySelector.Scope.CONTROL,
                acceptUnvalidatedWifi = true,
            ),
        )
        assertEquals(
            NetworkKind.WIFI,
            PolicySelector.select(
                Policy.WIFI_ONLY,
                unvalidatedWifi,
                PolicySelector.Scope.CONTROL,
                acceptUnvalidatedWifi = true,
            ),
        )
    }

    @Test
    fun exitTrafficStillDemandsAValidatedPath() {
        val unvalidatedWifi = NetworkAvailability(
            wifiAvailable = true,
            wifiValidated = false,
            cellularAvailable = true,
            cellularValidated = true,
        )
        // The relaxation is scoped to the control channel even when it is on.
        assertEquals(
            NetworkKind.CELLULAR,
            PolicySelector.select(
                Policy.AUTO,
                unvalidatedWifi,
                PolicySelector.Scope.EXIT,
                acceptUnvalidatedWifi = true,
            ),
        )
        assertEquals(
            NetworkKind.NONE,
            PolicySelector.select(
                Policy.WIFI_ONLY,
                unvalidatedWifi,
                PolicySelector.Scope.EXIT,
                acceptUnvalidatedWifi = true,
            ),
        )
    }

    @Test
    fun serverModeControlIsUnchanged() {
        val unvalidatedWifi = NetworkAvailability(
            wifiAvailable = true,
            wifiValidated = false,
            cellularAvailable = true,
            cellularValidated = true,
        )
        // No personal-mode server on the LAN, so the default keeps the old rule.
        assertEquals(
            NetworkKind.CELLULAR,
            PolicySelector.select(Policy.AUTO, unvalidatedWifi, PolicySelector.Scope.CONTROL),
        )
        assertEquals(
            NetworkKind.NONE,
            PolicySelector.select(Policy.WIFI_ONLY, unvalidatedWifi, PolicySelector.Scope.CONTROL),
        )
        assertEquals(NetworkKind.CELLULAR, PolicySelector.select(Policy.AUTO, unvalidatedWifi))
    }

    @Test
    fun anAbsentWifiIsNeverSelectedEvenForALanServer() {
        val noWifi = NetworkAvailability(
            wifiAvailable = false,
            wifiValidated = false,
            cellularAvailable = false,
            cellularValidated = false,
        )
        assertEquals(
            NetworkKind.NONE,
            PolicySelector.select(
                Policy.AUTO,
                noWifi,
                PolicySelector.Scope.CONTROL,
                acceptUnvalidatedWifi = true,
            ),
        )
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
