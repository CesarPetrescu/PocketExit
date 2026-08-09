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
    fun select(policy: Policy, state: NetworkAvailability): NetworkKind {
        val wifiUsable = state.wifiAvailable && state.wifiValidated
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
