package com.photonspark.pocketexit.network

import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.Policy

data class NetworkAvailability(
    val wifiAvailable: Boolean,
    val wifiValidated: Boolean,
    val cellularAvailable: Boolean,
    val cellularValidated: Boolean,
)

object PolicySelector {
    /**
     * What the chosen network has to reach. [CONTROL] only has to reach the
     * server; [EXIT] carries somebody's traffic out to the Internet.
     */
    enum class Scope { CONTROL, EXIT }

    /**
     * [acceptUnvalidatedWifi] relaxes the validation requirement for the
     * control channel only, and only for a personal-mode server that lives at a
     * private address. Such a server is one hop away over a Wi-Fi that Android
     * will never mark validated — a phone hotspot, a LAN-only router, a captive
     * network — so requiring validation there means never reaching it at all.
     * Exit traffic genuinely needs a validated path to the Internet and is left
     * strictly as it was.
     */
    fun select(
        policy: Policy,
        state: NetworkAvailability,
        scope: Scope = Scope.EXIT,
        acceptUnvalidatedWifi: Boolean = false,
    ): NetworkKind {
        val wifiUsable = state.wifiAvailable &&
            (state.wifiValidated || (scope == Scope.CONTROL && acceptUnvalidatedWifi))
        val cellularUsable = state.cellularAvailable && state.cellularValidated

        return when (policy) {
            Policy.WIFI_ONLY -> if (wifiUsable) NetworkKind.WIFI else NetworkKind.NONE
            Policy.CELLULAR_ONLY -> if (cellularUsable) NetworkKind.CELLULAR else NetworkKind.NONE
            Policy.WIFI_PREFERRED -> when {
                wifiUsable -> NetworkKind.WIFI
                cellularUsable -> NetworkKind.CELLULAR
                else -> NetworkKind.NONE
            }
            Policy.CELLULAR_PREFERRED -> when {
                cellularUsable -> NetworkKind.CELLULAR
                wifiUsable -> NetworkKind.WIFI
                else -> NetworkKind.NONE
            }
            Policy.AUTO -> when {
                wifiUsable -> NetworkKind.WIFI
                cellularUsable -> NetworkKind.CELLULAR
                else -> NetworkKind.NONE
            }
        }
    }
}
