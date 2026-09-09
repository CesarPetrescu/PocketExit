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

    @Test
    fun recognisesNodeIdsTheRegistryAccepts() {
        assertTrue(AgentConfig.isValidNodeId("pixel-8-a1b2c3d4"))
        assertTrue(AgentConfig.isValidNodeId("Node_1.2-3"))
        assertTrue(AgentConfig.isValidNodeId("a".repeat(64)))
        assertFalse(AgentConfig.isValidNodeId(""))
        assertFalse(AgentConfig.isValidNodeId("bad node"))
        assertFalse(AgentConfig.isValidNodeId("nod\u00e9"))
        assertFalse(AgentConfig.isValidNodeId("a".repeat(65)))
    }

    @Test
    fun readsAPrivateServerHostWithoutResolvingNames() {
        listOf(
            "https://192.168.1.50:8443",
            "https://10.0.0.4",
            "https://172.16.5.5",
            "https://172.31.255.255",
            "https://127.0.0.1:8443",
            "https://169.254.10.10",
            "https://100.100.0.1",
            "https://localhost:8443",
            "https://[fd00::1]:8443",
            "https://[fe80::1%25wlan0]:8443",
            "https://[::1]:8443",
        ).forEach { url ->
            assertTrue(url, AgentConfig.hasPrivateHost(url))
        }
        listOf(
            "https://proxy.example.com",
            "https://8.8.8.8",
            "https://172.32.0.1",
            "https://172.15.0.1",
            "https://192.169.1.1",
            "https://100.128.0.1",
            "https://[2606:4700::1111]",
            "https://999.1.1.1",
            "not a url at all",
            "",
        ).forEach { url ->
            assertFalse(url, AgentConfig.hasPrivateHost(url))
        }
    }

    @Test
    fun onlyAPersonalModeServerOnTheLanRelaxesControlValidation() {
        // Server mode: a public host and no pin, so nothing is relaxed.
        assertFalse(config().personalMode)
        assertFalse(config().controlAcceptsUnvalidatedWifi)
        // Pinned, but reachable only through the Internet: exit-strength
        // validation is still the right rule for the control channel too.
        assertTrue(config().copy(pin = PIN).personalMode)
        assertFalse(config().copy(pin = PIN).controlAcceptsUnvalidatedWifi)
        // The laptop on the LAN, pinned or behind its own certificate.
        val lan = config("https://192.168.1.50:8443")
        assertTrue(lan.personalMode)
        assertTrue(lan.controlAcceptsUnvalidatedWifi)
        assertTrue(lan.copy(pin = PIN).controlAcceptsUnvalidatedWifi)
    }

    private companion object {
        // base64url_nopad(SHA-256(DER SubjectPublicKeyInfo)) of the test
        // certificate in PinnedTrustTest.
        const val PIN = "oYWADqlFFAIRdrwOqHkplvisUDN0UlSymbg4PPUQnVw"
    }
}
