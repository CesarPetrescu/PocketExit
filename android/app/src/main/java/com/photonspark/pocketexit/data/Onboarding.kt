package com.photonspark.pocketexit.data

import java.net.URI
import java.net.URLDecoder

/**
 * The two shapes a `pocketexit://configure` link can take. Version 1 carries a
 * hand-minted token and yields a usable configuration outright. Version 2
 * carries a pairing code instead, so it cannot yield a configuration: the token
 * does not exist until the phone claims one from the server.
 */
sealed interface OnboardingLink {
    data class Configured(val config: AgentConfig) : OnboardingLink

    data class Pairing(
        val serverUrl: String,
        val pairingCode: String,
        val pin: String,
        val serverName: String,
    ) : OnboardingLink
}

fun AgentConfig.parseOnboardingUri(raw: String): OnboardingLink {
    val values = onboardingFields(raw)
    return when (values["v"]) {
        "1" -> OnboardingLink.Configured(version1(values))
        "2" -> version2(values)
        else -> throw IllegalArgumentException("Unsupported onboarding version")
    }
}

/**
 * Version 1 only. A version 2 link needs the pairing flow, so it is rejected
 * here rather than silently producing a configuration with no token.
 */
fun AgentConfig.withOnboardingUri(raw: String): AgentConfig =
    when (val link = parseOnboardingUri(raw)) {
        is OnboardingLink.Configured -> link.config
        is OnboardingLink.Pairing -> throw IllegalArgumentException("This link must be paired first")
    }

private fun onboardingFields(raw: String): Map<String, String> {
    val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Invalid onboarding link") }
    require(uri.scheme.equals("pocketexit", ignoreCase = true) &&
        uri.host.equals("configure", ignoreCase = true)
    ) { "Not a PocketExit onboarding link" }
    require(uri.rawFragment == null && uri.rawUserInfo == null && uri.rawPath.orEmpty().isEmpty()) {
        "Invalid onboarding link"
    }
    val values = linkedMapOf<String, String>()
    uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { field ->
        val parts = field.split('=', limit = 2)
        val key = decode(parts[0])
        val value = decode(parts.getOrElse(1) { "" })
        require(values.put(key, value) == null) { "Duplicate onboarding field" }
    }
    return values
}

private fun AgentConfig.version1(values: Map<String, String>): AgentConfig {
    requireKnownFields(values, V1_FIELDS)
    val candidate = copy(
        serverUrl = values["server"].orEmpty(),
        nodeId = values["node"].orEmpty(),
        agentToken = values["token"].orEmpty(),
        // A version 1 server is reached through the platform trust store, so a
        // pin left over from an earlier pairing must not survive the import.
        pin = "",
        enabled = false,
    )
    candidate.validationError()?.let { throw IllegalArgumentException(it) }
    return candidate
}

private fun version2(values: Map<String, String>): OnboardingLink.Pairing {
    requireKnownFields(values, V2_FIELDS)
    val serverUrl = values["server"].orEmpty().trim().trimEnd('/')
    AgentConfig.serverUrlError(serverUrl)?.let { throw IllegalArgumentException(it) }
    val pairingCode = values["pair"].orEmpty().trim()
    require(pairingCode.isNotEmpty()) { "Pairing code is required" }
    require(pairingCode.length <= MAX_PAIRING_CODE_LENGTH) { "Pairing code is too long" }
    // The pin is optional: a personal-mode server behind a real certificate
    // pairs without pinning. Present but malformed is a rejection, not a
    // silent fall back to platform trust, so an empty fp is a rejection too.
    val fingerprint = values["fp"]
    val pin = fingerprint?.trim().orEmpty()
    require(fingerprint == null || AgentConfig.isValidPin(pin)) { AgentConfig.PIN_ERROR }
    val serverName = values["name"].orEmpty().trim()
    require(serverName.length <= MAX_SERVER_NAME_LENGTH) {
        "Server name must be at most $MAX_SERVER_NAME_LENGTH characters"
    }
    return OnboardingLink.Pairing(
        serverUrl = serverUrl,
        pairingCode = pairingCode,
        pin = pin,
        serverName = serverName,
    )
}

private fun requireKnownFields(values: Map<String, String>, allowed: Set<String>) {
    require(values.keys.all { it in allowed }) { "Unsupported onboarding field" }
}

private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

private val V1_FIELDS = setOf("v", "server", "node", "token")
private val V2_FIELDS = setOf("v", "server", "pair", "fp", "name")
private const val MAX_PAIRING_CODE_LENGTH = 64
private const val MAX_SERVER_NAME_LENGTH = 64
