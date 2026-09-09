package com.photonspark.pocketexit.ui

import com.photonspark.pocketexit.data.AgentRuntime
import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.NetworkSnapshot
import com.photonspark.pocketexit.data.Policy
import com.photonspark.pocketexit.network.NetworkAvailability
import com.photonspark.pocketexit.network.PolicySelector

/**
 * What the person is actually looking at. The service reports a running flag, a
 * registered flag and a heartbeat timestamp; on their own those three collapse
 * into "on" and "off" and hide the two failures that matter — a phone that is
 * running with nothing to relay through, and a phone whose server stopped
 * answering.
 */
internal enum class ConnectionState {
    STOPPED,
    CONNECTING,
    ONLINE,
    DEGRADED,
    NO_NETWORK,
}

/**
 * The service heartbeats every 15 seconds, so three missed beats is a link that
 * is genuinely stuck rather than one that happens to be mid-request.
 */
private const val HEARTBEAT_STALE_MS = 45_000L

internal fun connectionState(runtime: AgentRuntime, nowMs: Long): ConnectionState = when {
    !runtime.running -> ConnectionState.STOPPED
    !runtime.wifi.usable && !runtime.cellular.usable -> ConnectionState.NO_NETWORK
    runtime.lastHeartbeatEpochMs == 0L -> ConnectionState.CONNECTING
    nowMs - runtime.lastHeartbeatEpochMs > HEARTBEAT_STALE_MS -> ConnectionState.DEGRADED
    !runtime.registered -> ConnectionState.DEGRADED
    else -> ConnectionState.ONLINE
}

/**
 * Which network the exit traffic would leave through right now. The service
 * picks this per circuit rather than publishing it, so the UI re-runs the same
 * selector over the snapshots the service already publishes.
 */
internal fun exitNetwork(runtime: AgentRuntime, policy: Policy): NetworkKind = PolicySelector.select(
    policy,
    NetworkAvailability(
        wifiAvailable = runtime.wifi.available,
        wifiValidated = runtime.wifi.validated,
        cellularAvailable = runtime.cellular.available,
        cellularValidated = runtime.cellular.validated,
    ),
)

internal fun AgentRuntime.snapshotFor(kind: NetworkKind): NetworkSnapshot? = when (kind) {
    NetworkKind.WIFI -> wifi
    NetworkKind.CELLULAR -> cellular
    NetworkKind.NONE -> null
}
