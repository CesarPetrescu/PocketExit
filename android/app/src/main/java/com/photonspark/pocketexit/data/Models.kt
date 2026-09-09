package com.photonspark.pocketexit.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.Locale

enum class Policy(val wire: String, val label: String) {
    AUTO("AUTO", "Automatic"),
    WIFI_ONLY("WIFI_ONLY", "Wi-Fi only"),
    CELLULAR_ONLY("CELLULAR_ONLY", "Cellular only"),
    WIFI_PREFERRED("WIFI_PREFERRED", "Wi-Fi preferred"),
    CELLULAR_PREFERRED("CELLULAR_PREFERRED", "Cellular preferred");

    companion object {
        fun fromWire(value: String?, fallback: Policy = AUTO): Policy =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: fallback
    }
}

enum class NetworkKind(val wire: String, val label: String) {
    WIFI("WIFI", "Wi-Fi"),
    CELLULAR("CELLULAR", "Cellular"),
    NONE("NONE", "None");
}

data class NetworkSnapshot(
    val available: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val interfaceName: String = "",
    val addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = 0,
    val downKbps: Int = 0,
    val upKbps: Int = 0,
) {
    val usable: Boolean get() = available && validated

    fun toJson(): JSONObject = JSONObject()
        .put("available", available)
        .put("validated", validated)
        .put("metered", metered)
        .put("interface_name", interfaceName)
        .put("addresses", JSONArray(addresses))
        .put("dns_servers", JSONArray(dnsServers))
        .put("mtu", mtu)
        .put("down_kbps", downKbps)
        .put("up_kbps", upKbps)
}

data class AgentConfig(
    val serverUrl: String,
    val nodeId: String,
    val deviceName: String,
    val agentToken: String,
    val controlPolicy: Policy,
    val exitPolicy: Policy,
    val enabled: Boolean,
    val autoStart: Boolean,
    // Personal mode authenticates the server by the SHA-256 of its leaf
    // SubjectPublicKeyInfo, carried out of band in the pairing QR. Empty means
    // the platform trust store decides, as it always does in server mode.
    val pin: String = "",
) {
    val normalizedServerUrl: String
        get() = serverUrl.trim().trimEnd('/')

    fun validationError(): String? {
        serverUrlError(normalizedServerUrl)?.let { return it }
        return when {
            nodeId.isBlank() -> "Node ID is required"
            !nodeId.matches(Regex("[A-Za-z0-9._-]{1,64}")) ->
                "Node ID may contain letters, digits, dots, underscores, and dashes"
            deviceName.trim().isEmpty() -> "Device name is required"
            deviceName.length > 128 -> "Device name must be at most 128 characters"
            agentToken.isBlank() -> "Agent token is required"
            agentToken.length < 16 -> "Agent token must be at least 16 characters"
            agentToken.length > 4_096 -> "Agent token is too long"
            pin.isNotEmpty() && !isValidPin(pin) -> PIN_ERROR
            else -> null
        }
    }

    companion object {
        // SHA-256 over the DER SubjectPublicKeyInfo, so a pin is always 32 bytes.
        const val PIN_BYTES = 32
        const val PIN_ERROR = "Certificate pin must be 32 bytes encoded as base64url"

        // Shared with the onboarding parser, which validates a server origin
        // before any node identity exists to validate alongside it.
        fun serverUrlError(serverUrl: String): String? {
            val uri = serverUrl.takeIf(String::isNotBlank)?.let {
                runCatching { URI(it) }.getOrNull()
            }
            return when {
                serverUrl.isBlank() -> "Server URL is required"
                uri == null -> "Server URL is invalid"
                !uri.scheme.equals("https", ignoreCase = true) -> "Server URL must use HTTPS"
                uri.host.isNullOrBlank() -> "Server URL must contain a host"
                uri.rawUserInfo != null -> "Server URL must not contain credentials"
                uri.rawQuery != null || uri.rawFragment != null ->
                    "Server URL must not contain a query or fragment"
                !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/" ->
                    "Server URL must not contain a path"
                uri.port !in -1..65_535 -> "Server URL contains an invalid port"
                else -> null
            }
        }

        // Padding and the standard base64 alphabet are both rejected: the pin
        // travels in a URI query string, so it is always base64url without
        // padding.
        fun isValidPin(pin: String): Boolean {
            if (pin.isEmpty() || pin.any { it !in PIN_ALPHABET }) return false
            val decoded = runCatching { Base64.getUrlDecoder().decode(pin) }.getOrNull()
            return decoded != null && decoded.size == PIN_BYTES
        }

        private const val PIN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}

data class AgentRuntime(
    val running: Boolean = false,
    val registered: Boolean = false,
    val statusMessage: String = "Stopped",
    val activeControlNetwork: NetworkKind = NetworkKind.NONE,
    val negotiatedProtocol: String = "—",
    val wifi: NetworkSnapshot = NetworkSnapshot(),
    val cellular: NetworkSnapshot = NetworkSnapshot(),
    val activeCircuits: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val lastHeartbeatEpochMs: Long = 0,
    val lastError: String = "",
)

data class AgentCommand(
    val type: String,
    val circuitId: String = "",
    val targetHost: String = "",
    val targetPort: Int = 0,
    val exitPolicy: Policy? = null,
    val controlPolicy: Policy? = null,
    val allowPrivate: Boolean = false,
) {
    companion object {
        fun fromJson(json: JSONObject): AgentCommand = AgentCommand(
            type = json.optString("type").lowercase(Locale.US),
            circuitId = json.optString("circuit_id"),
            targetHost = json.optString("target_host"),
            targetPort = json.optInt("target_port"),
            exitPolicy = json.optString("exit_policy").takeIf(String::isNotBlank)?.let {
                Policy.fromWire(it)
            },
            controlPolicy = json.optString("control_policy").takeIf(String::isNotBlank)?.let {
                Policy.fromWire(it)
            },
            allowPrivate = json.optBoolean("allow_private", false),
        )
    }
}

data class HeartbeatData(
    val config: AgentConfig,
    val activeControlNetwork: NetworkKind,
    val wifi: NetworkSnapshot,
    val cellular: NetworkSnapshot,
    val batteryPercent: Int,
    val charging: Boolean,
    val activeCircuits: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val transportProtocol: String,
) {
    fun toJson(appVersion: String): JSONObject = JSONObject()
        .put("node_id", config.nodeId)
        .put("device_name", config.deviceName)
        .put("app_version", appVersion)
        .put("control_policy", config.controlPolicy.wire)
        .put("exit_policy", config.exitPolicy.wire)
        .put("active_control_network", activeControlNetwork.wire)
        .put("transport_protocol", transportProtocol)
        .put("wifi", wifi.toJson())
        .put("cellular", cellular.toJson())
        .put("battery_percent", batteryPercent.coerceIn(0, 100))
        .put("charging", charging)
        .put("active_circuits", activeCircuits.coerceAtLeast(0))
        .put("bytes_up", bytesUp.coerceAtLeast(0))
        .put("bytes_down", bytesDown.coerceAtLeast(0))
}
