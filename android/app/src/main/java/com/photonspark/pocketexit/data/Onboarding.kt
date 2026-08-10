package com.photonspark.pocketexit.data

import java.net.URI
import java.net.URLDecoder

fun AgentConfig.withOnboardingUri(raw: String): AgentConfig {
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
    require(values["v"] == "1") { "Unsupported onboarding version" }
    val candidate = copy(
        serverUrl = values["server"].orEmpty(),
        nodeId = values["node"].orEmpty(),
        agentToken = values["token"].orEmpty(),
        enabled = false,
    )
    candidate.validationError()?.let { throw IllegalArgumentException(it) }
    return candidate
}

private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
