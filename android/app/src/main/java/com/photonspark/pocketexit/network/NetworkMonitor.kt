package com.photonspark.pocketexit.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.NetworkSnapshot
import com.photonspark.pocketexit.data.Policy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    data class Inventory(
        val wifi: NetworkSnapshot = NetworkSnapshot(),
        val cellular: NetworkSnapshot = NetworkSnapshot(),
    )

    data class BoundNetwork(
        val kind: NetworkKind,
        val network: Network,
        val snapshot: NetworkSnapshot,
    )

    private data class Tracked(
        val network: Network? = null,
        val snapshot: NetworkSnapshot = NetworkSnapshot(),
    )

    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var wifi = Tracked()
    private var cellular = Tracked()
    private var started = false

    private val mutableInventory = MutableStateFlow(Inventory())
    val inventory: StateFlow<Inventory> = mutableInventory.asStateFlow()

    private val wifiCallback = callbackFor(NetworkKind.WIFI)
    private val cellularCallback = callbackFor(NetworkKind.CELLULAR)

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }

        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { connectivity.registerNetworkCallback(wifiRequest, wifiCallback) }
        // requestNetwork keeps a cellular Network available while Wi-Fi remains the
        // default route. This is a normal public API and does not change process routing.
        runCatching { connectivity.requestNetwork(cellularRequest, cellularCallback) }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
        }
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        runCatching { connectivity.unregisterNetworkCallback(cellularCallback) }
        synchronized(lock) {
            wifi = Tracked()
            cellular = Tracked()
            publishLocked()
        }
    }

    fun select(policy: Policy): BoundNetwork? = synchronized(lock) {
        val availability = NetworkAvailability(
            wifiAvailable = wifi.snapshot.available,
            wifiValidated = wifi.snapshot.validated,
            cellularAvailable = cellular.snapshot.available,
            cellularValidated = cellular.snapshot.validated,
        )
        when (PolicySelector.select(policy, availability)) {
            NetworkKind.WIFI -> wifi.network?.let { BoundNetwork(NetworkKind.WIFI, it, wifi.snapshot) }
            NetworkKind.CELLULAR -> cellular.network?.let {
                BoundNetwork(NetworkKind.CELLULAR, it, cellular.snapshot)
            }
            NetworkKind.NONE -> null
        }
    }

    private fun callbackFor(kind: NetworkKind) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(kind, network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            update(kind, network, capabilities = capabilities)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            update(kind, network, linkProperties = linkProperties)

        override fun onLost(network: Network) {
            synchronized(lock) {
                val current = tracked(kind)
                if (current.network?.networkHandle == network.networkHandle) {
                    setTracked(kind, Tracked())
                    publishLocked()
                }
            }
        }

        override fun onUnavailable() {
            synchronized(lock) {
                setTracked(kind, Tracked())
                publishLocked()
            }
        }
    }

    private fun update(
        kind: NetworkKind,
        network: Network,
        capabilities: NetworkCapabilities? = null,
        linkProperties: LinkProperties? = null,
    ) {
        synchronized(lock) {
            val actualCapabilities = capabilities ?: connectivity.getNetworkCapabilities(network)
            val actualLinkProperties = linkProperties ?: connectivity.getLinkProperties(network)
            val snapshot = NetworkSnapshot(
                available = actualCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                validated = actualCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                metered = actualCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
                interfaceName = actualLinkProperties?.interfaceName.orEmpty(),
                addresses = actualLinkProperties?.linkAddresses
                    ?.mapNotNull { it.address.hostAddress }
                    ?.distinct()
                    .orEmpty(),
                dnsServers = actualLinkProperties?.dnsServers
                    ?.mapNotNull { it.hostAddress }
                    ?.distinct()
                    .orEmpty(),
                mtu = actualLinkProperties?.mtu ?: 0,
                downKbps = actualCapabilities?.linkDownstreamBandwidthKbps ?: 0,
                upKbps = actualCapabilities?.linkUpstreamBandwidthKbps ?: 0,
            )
            setTracked(kind, Tracked(network, snapshot))
            publishLocked()
        }
    }

    private fun tracked(kind: NetworkKind): Tracked = when (kind) {
        NetworkKind.WIFI -> wifi
        NetworkKind.CELLULAR -> cellular
        NetworkKind.NONE -> Tracked()
    }

    private fun setTracked(kind: NetworkKind, tracked: Tracked) {
        when (kind) {
            NetworkKind.WIFI -> wifi = tracked
            NetworkKind.CELLULAR -> cellular = tracked
            NetworkKind.NONE -> Unit
        }
    }

    private fun publishLocked() {
        mutableInventory.value = Inventory(wifi.snapshot, cellular.snapshot)
    }
}
