package com.photonspark.pocketexit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun acceptsAWellFormedCertificatePinAndNoPinAtAll() {
        assertNull(config().copy(pin = PIN).validationError())
        assertNull(config().copy(pin = "").validationError())
        assertTrue(AgentConfig.isValidPin(PIN))
    }

    @Test
    fun rejectsPinsThatAreNotThirtyTwoBase64urlBytes() {
        // 42 characters decode to 31 bytes: the right shape, the wrong length.
        assertEquals(AgentConfig.PIN_ERROR, config().copy(pin = PIN.dropLast(1)).validationError())
        assertEquals(AgentConfig.PIN_ERROR, config().copy(pin = PIN + "A").validationError())
        assertEquals(AgentConfig.PIN_ERROR, config().copy(pin = PIN.dropLast(1) + "=").validationError())
        assertEquals(AgentConfig.PIN_ERROR, config().copy(pin = "+" + PIN.drop(1)).validationError())
        assertEquals(AgentConfig.PIN_ERROR, config().copy(pin = "not-a-fingerprint").validationError())
        assertFalse(AgentConfig.isValidPin(PIN.dropLast(1)))
        assertFalse(AgentConfig.isValidPin(""))
    }

    @Test
    fun validatesIdentityFields() {
        assertEquals("Node ID may contain letters, digits, dots, underscores, and dashes", config().copy(nodeId = "bad node").validationError())
        assertEquals("Device name is required", config().copy(deviceName = " ").validationError())
        assertEquals("Agent token is required", config().copy(agentToken = "").validationError())
        assertEquals("Agent token must be at least 16 characters", config().copy(agentToken = "too-short").validationError())
    }

    private companion object {
        // base64url_nopad(SHA-256(DER SubjectPublicKeyInfo)) of the test
        // certificate in PinnedTrustTest.
        const val PIN = "oYWADqlFFAIRdrwOqHkplvisUDN0UlSymbg4PPUQnVw"
    }
}
