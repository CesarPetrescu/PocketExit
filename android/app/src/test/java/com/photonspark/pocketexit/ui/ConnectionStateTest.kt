package com.photonspark.pocketexit.ui

import com.photonspark.pocketexit.data.AgentRuntime
import com.photonspark.pocketexit.data.NetworkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun anUnvalidatedWifiIsNoNetworkForAnOrdinaryServer() {
        assertEquals(ConnectionState.NO_NETWORK, connectionState(runtime = onLanWifi, nowMs = NOW))
    }

    @Test
    fun anUnvalidatedWifiCarriesAPersonalModeServerOnTheLan() {
        // The same phone, the same Wi-Fi, and a laptop one hop away: the
        // control channel is working, so the screen must not claim there is no
        // network to work over.
        assertEquals(
            ConnectionState.ONLINE,
            connectionState(
                runtime = onLanWifi,
                nowMs = NOW,
                controlAcceptsUnvalidatedWifi = true,
            ),
        )
    }

    @Test
    fun theRelaxationDoesNotInventANetworkThatIsNotThere() {
        val nothing = onLanWifi.copy(wifi = NetworkSnapshot())
        assertEquals(
            ConnectionState.NO_NETWORK,
            connectionState(runtime = nothing, nowMs = NOW, controlAcceptsUnvalidatedWifi = true),
        )
    }

    @Test
    fun aStaleHeartbeatIsStillDegradedOnAnUnvalidatedWifi() {
        assertEquals(
            ConnectionState.DEGRADED,
            connectionState(
                runtime = onLanWifi.copy(lastHeartbeatEpochMs = NOW - 60_000L),
                nowMs = NOW,
                controlAcceptsUnvalidatedWifi = true,
            ),
        )
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        // Wi-Fi that claims Internet and will never be validated, which is what
        // a phone hotspot or a LAN-only router looks like.
        val onLanWifi = AgentRuntime(
            running = true,
            registered = true,
            wifi = NetworkSnapshot(available = true, validated = false),
            cellular = NetworkSnapshot(),
            lastHeartbeatEpochMs = NOW - 2_000L,
        )
    }
}
