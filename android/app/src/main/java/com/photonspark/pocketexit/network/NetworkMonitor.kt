package com.photonspark.pocketexit.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.NetworkSnapshot
import com.photonspark.pocketexit.data.Policy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * [scope] and [acceptUnvalidatedWifi] are passed straight to
     * [PolicySelector]: the control channel of a personal-mode server on the
     * local network may use a Wi-Fi that Android has not validated, and nothing
     * else may.
     */
    fun select(
        policy: Policy,
        scope: PolicySelector.Scope = PolicySelector.Scope.EXIT,
        acceptUnvalidatedWifi: Boolean = false,
    ): BoundNetwork? = synchronized(lock) {
        val availability = NetworkAvailability(
            wifiAvailable = wifi.snapshot.available,
            wifiValidated = wifi.snapshot.validated,
            cellularAvailable = cellular.snapshot.available,
            cellularValidated = cellular.snapshot.validated,
        )
        when (PolicySelector.select(policy, availability, scope, acceptUnvalidatedWifi)) {
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
                mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    actualLinkProperties?.mtu ?: 0
                } else {
                    0
                },
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

    /** Answers the first Wi-Fi network the platform offers, once. */
    private class WifiProbe : ConnectivityManager.NetworkCallback() {
        val answer = CompletableDeferred<Network?>()

        override fun onAvailable(network: Network) {
            answer.complete(network)
        }

        override fun onUnavailable() {
            answer.complete(null)
        }
    }

    companion object {
        /**
         * The Wi-Fi network this phone can reach right now, or null when there
         * is none worth binding to and the default route is the best there is.
         *
         * Pairing needs a network before any [NetworkMonitor] is running, and
         * it needs Wi-Fi specifically: a personal-mode laptop answers on the
         * LAN, which is exactly what the OS default route often is not.
         *
         * [requireValidated] carries the same distinction [PolicySelector]
         * draws. A server on the LAN is reachable over a Wi-Fi the system will
         * never validate; a server out on the Internet is not, so there an
         * unvalidated Wi-Fi is worse than the default route and is refused.
         */
        suspend fun awaitWifi(
            context: Context,
            requireValidated: Boolean,
            timeoutMs: Int = WIFI_PROBE_TIMEOUT_MS,
        ): Network? {
            val connectivity = context.applicationContext
                .getSystemService(ConnectivityManager::class.java) ?: return null
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val probe = WifiProbe()
            // The timeout overload answers onUnavailable rather than waiting
            // forever when the phone has no Wi-Fi at all.
            if (runCatching { connectivity.requestNetwork(request, probe, timeoutMs) }.isFailure) {
                return null
            }
            val network = try {
                withTimeoutOrNull(timeoutMs + PROBE_GRACE_MS) { probe.answer.await() }
            } finally {
                runCatching { connectivity.unregisterNetworkCallback(probe) }
            } ?: return null
            // Validation is asked of the network rather than of the request:
            // it is a mutable capability, and requests carrying one are
            // rejected outright on some releases.
            if (!requireValidated) return network
            val validated = connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            return if (validated) network else null
        }

        private const val WIFI_PROBE_TIMEOUT_MS = 3_000
        private const val PROBE_GRACE_MS = 1_000L
    }
}
