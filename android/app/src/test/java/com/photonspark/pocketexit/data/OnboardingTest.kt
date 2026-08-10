package com.photonspark.pocketexit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OnboardingTest {
    private val current = AgentConfig(
        serverUrl = "https://old.example.com",
        nodeId = "old-node",
        deviceName = "Test Phone",
        agentToken = "old-agent-token-1234",
        controlPolicy = Policy.WIFI_PREFERRED,
        exitPolicy = Policy.CELLULAR_ONLY,
        enabled = true,
        autoStart = true,
    )

    @Test
    fun importsValidatedConfigurationAndDisablesAgent() {
        val imported = current.withOnboardingUri(
            "pocketexit://configure?v=1&server=https%3A%2F%2Fproxy.example.com&node=s24u&token=new-agent-token-1234",
        )
        assertEquals("https://proxy.example.com", imported.serverUrl)
        assertEquals("s24u", imported.nodeId)
        assertEquals("new-agent-token-1234", imported.agentToken)
        assertFalse(imported.enabled)
        assertEquals(Policy.CELLULAR_ONLY, imported.exitPolicy)
    }

    @Test
    fun rejectsUnsafeOrIncompleteLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            current.withOnboardingUri(
                "pocketexit://configure?v=1&server=http%3A%2F%2Fproxy.example.com&node=s24u&token=new-agent-token-1234",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            current.withOnboardingUri("pocketexit://configure?v=1&server=https%3A%2F%2Fproxy.example.com&node=s24u")
        }
    }
}
