package com.photonspark.pocketexit.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

class PinnedTrustTest {
    @Test
    fun rejectsAnEmptyPinAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { PinnedTrustManager("") }
        assertThrows(IllegalArgumentException::class.java) { PinnedTrustManager("   ") }
    }

    @Test
    fun leavesAnUnpinnedBuilderOnThePlatformTrustStore() {
        val builder = OkHttpClient.Builder()
        assertSame(builder, PinnedTrust.applyPin(builder, ""))
        assertSame(builder, PinnedTrust.applyPin(builder, "  "))
    }

    @Test
    fun fingerprintsTheSubjectPublicKeyInfo() {
        assertEquals(PIN, PinnedTrust.fingerprint(certificate()))
    }

    @Test
    fun acceptsTheLeafThatMatchesThePin() {
        PinnedTrustManager(PIN).checkServerTrusted(arrayOf(certificate()), "ECDHE_ECDSA")
    }

    @Test
    fun namesBothPinsWhenTheLeafDoesNotMatch() {
        val mismatch = assertThrows(CertificateException::class.java) {
            PinnedTrustManager(OTHER_PIN).checkServerTrusted(arrayOf(certificate()), "ECDHE_ECDSA")
        }
        assertTrue(mismatch.message.orEmpty().contains(OTHER_PIN))
        assertTrue(mismatch.message.orEmpty().contains(PIN))
    }

    @Test
    fun rejectsAnEmptyChainAndEveryClientCertificate() {
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(PIN).checkServerTrusted(emptyArray<X509Certificate>(), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(PIN).checkServerTrusted(null, "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(PIN).checkClientTrusted(arrayOf(certificate()), "ECDHE_ECDSA")
        }
    }

    @Test
    fun advertisesNoIssuersSoNoChainIsEverWalked() {
        assertEquals(0, PinnedTrustManager(PIN).acceptedIssuers.size)
    }

    private fun certificate(): X509Certificate {
        val der = ByteArrayInputStream(Base64.getDecoder().decode(CERTIFICATE))
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(der) as X509Certificate
    }

    private companion object {
        const val PIN = "oYWADqlFFAIRdrwOqHkplvisUDN0UlSymbg4PPUQnVw"
        const val OTHER_PIN = "AYWADqlFFAIRdrwOqHkplvisUDN0UlSymbg4PPUQnVw"

        // Self-signed ECDSA P-256 leaf, CN=PocketExit Personal, of the shape
        // the personal-mode server generates.
        const val CERTIFICATE =
            "MIIBrTCCAVOgAwIBAgIUN2WqCj3q/X4+nwKHiJkZBhmlgi0wCgYIKoZIzj0EAwIwHjEcMBoG" +
            "A1UEAwwTUG9ja2V0RXhpdCBQZXJzb25hbDAeFw0yNjA5MDkxODMwMzVaFw0yNzEwMTExODMw" +
            "MzVaMB4xHDAaBgNVBAMME1BvY2tldEV4aXQgUGVyc29uYWwwWTATBgcqhkjOPQIBBggqhkjO" +
            "PQMBBwNCAAQgAAhRnfn7Kt7WlGeAvw6hm+bGe4q41fV+X7tMroHwud1tEAvMdEesZD+V2C8s" +
            "dC02tv/E8noCSqlxIc9zY9OYo28wbTAdBgNVHQ4EFgQUOIn0+LW8ZzLSf56nXwU7VY75Cmsw" +
            "HwYDVR0jBBgwFoAUOIn0+LW8ZzLSf56nXwU7VY75CmswDwYDVR0TAQH/BAUwAwEB/zAaBgNV" +
            "HREEEzARgglsb2NhbGhvc3SHBH8AAAEwCgYIKoZIzj0EAwIDSAAwRQIgKqZlTmaE51Y4wVx4" +
            "mjgh7SYml3PFvDQ08IXusSiDRncCIQClSHdMFiuQjGaIUI8vY2r2MvHSxfYV0QM0iTymvgVV" +
            "SQ=="
    }
}
