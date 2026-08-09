package com.photonspark.pocketexit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConfigTest {
    private fun config(url: String = "https://proxy.example.com") = AgentConfig(
        serverUrl = url,
        nodeId = "s24u",
        deviceName = "S24 Ultra",
        agentToken = "agent-token-test-2026",
        controlPolicy = Policy.WIFI_PREFERRED,
        exitPolicy = Policy.CELLULAR_ONLY,
        enabled = true,
        autoStart = false,
    )

    @Test
    fun acceptsCanonicalHttpsOrigin() {
        assertNull(config().validationError())
        assertEquals("https://proxy.example.com", config("https://proxy.example.com/").normalizedServerUrl)
    }

    @Test
    fun rejectsCleartextAndNonOriginUrls() {
        assertEquals("Server URL must use HTTPS", config("http://proxy.example.com").validationError())
        assertEquals("Server URL must not contain credentials", config("https://user@proxy.example.com").validationError())
        assertEquals("Server URL must not contain a path", config("https://proxy.example.com/agent").validationError())
        assertEquals("Server URL must not contain a query or fragment", config("https://proxy.example.com?x=1").validationError())
    }

    @Test
    fun validatesIdentityFields() {
        assertEquals("Node ID may contain letters, digits, dots, underscores, and dashes", config().copy(nodeId = "bad node").validationError())
        assertEquals("Device name is required", config().copy(deviceName = " ").validationError())
        assertEquals("Agent token is required", config().copy(agentToken = "").validationError())
        assertEquals("Agent token must be at least 16 characters", config().copy(agentToken = "too-short").validationError())
    }
}
