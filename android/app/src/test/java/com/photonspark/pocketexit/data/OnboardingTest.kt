package com.photonspark.pocketexit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun version1ClearsAPinLeftBehindByAnEarlierPairing() {
        val paired = current.copy(pin = PIN)
        val imported = paired.withOnboardingUri(
            "pocketexit://configure?v=1&server=https%3A%2F%2Fproxy.example.com&node=s24u&token=new-agent-token-1234",
        )
        assertEquals("", imported.pin)
    }

    @Test
    fun parsesPairingLink() {
        val link = current.parseOnboardingUri(
            "pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4" +
                "&fp=$PIN&name=Cesar%27s+laptop",
        )
        assertTrue(link is OnboardingLink.Pairing)
        val pairing = link as OnboardingLink.Pairing
        assertEquals("https://192.168.1.50:8443", pairing.serverUrl)
        assertEquals("A1B2C3D4", pairing.pairingCode)
        assertEquals(PIN, pairing.pin)
        assertEquals("Cesar's laptop", pairing.serverName)
    }

    @Test
    fun parsesPairingLinkWithoutOptionalFields() {
        val link = current.parseOnboardingUri(
            "pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2-C3D4",
        ) as OnboardingLink.Pairing
        assertEquals("A1B2-C3D4", link.pairingCode)
        assertEquals("", link.pin)
        assertEquals("", link.serverName)
    }

    @Test
    fun aPairingLinkNeverYieldsAUsableConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            current.withOnboardingUri(
                "pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4",
            )
        }
    }

    @Test
    fun rejectsMalformedFingerprints() {
        listOf(
            PIN.dropLast(1), // 31 bytes
            PIN + "A", // 33 bytes
            PIN.dropLast(1) + "=", // padded
            "+" + PIN.drop(1), // standard base64 alphabet
            "not-a-fingerprint",
            "",
        ).forEach { fingerprint ->
            assertThrows(IllegalArgumentException::class.java) {
                current.parseOnboardingUri(
                    "pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443" +
                        "&pair=A1B2C3D4&fp=$fingerprint",
                )
            }
        }
    }

    @Test
    fun rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException::class.java) {
            current.parseOnboardingUri("pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443")
        }
        assertThrows(IllegalArgumentException::class.java) {
            current.parseOnboardingUri("pocketexit://configure?v=2&pair=A1B2C3D4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            current.parseOnboardingUri(
                "pocketexit://configure?v=2&server=http%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            current.parseOnboardingUri("pocketexit://configure?v=3&server=https%3A%2F%2Fproxy.example.com")
        }
    }

    @Test
    fun rejectsDuplicateAndUnknownFields() {
        assertEquals(
            "Duplicate onboarding field",
            assertThrows(IllegalArgumentException::class.java) {
                current.parseOnboardingUri(
                    "pocketexit://configure?v=2&v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4",
                )
            }.message,
        )
        assertEquals(
            "Unsupported onboarding field",
            assertThrows(IllegalArgumentException::class.java) {
                current.parseOnboardingUri(
                    "pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443" +
                        "&pair=A1B2C3D4&token=new-agent-token-1234",
                )
            }.message,
        )
        assertEquals(
            "Unsupported onboarding field",
            assertThrows(IllegalArgumentException::class.java) {
                current.parseOnboardingUri(
                    "pocketexit://configure?v=1&server=https%3A%2F%2Fproxy.example.com&node=s24u" +
                        "&token=new-agent-token-1234&fp=$PIN",
                )
            }.message,
        )
    }

    @Test
    fun rejectsPathsFragmentsAndUserinfo() {
        listOf(
            "pocketexit://configure/extra?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4",
            "pocketexit://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4#more",
            "pocketexit://mallory@configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4",
            "pocketexit://configure/extra?v=1&server=https%3A%2F%2Fproxy.example.com&node=s24u&token=new-agent-token-1234",
            "https://configure?v=2&server=https%3A%2F%2F192.168.1.50%3A8443&pair=A1B2C3D4",
        ).forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) { current.parseOnboardingUri(uri) }
        }
    }

    private companion object {
        // base64url_nopad(SHA-256(DER SubjectPublicKeyInfo)) of the test
        // certificate in PinnedTrustTest.
        const val PIN = "oYWADqlFFAIRdrwOqHkplvisUDN0UlSymbg4PPUQnVw"
    }
}
