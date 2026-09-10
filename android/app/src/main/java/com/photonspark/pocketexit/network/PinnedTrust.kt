package com.photonspark.pocketexit.network

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Authenticates a personal-mode server by the SHA-256 of its leaf
 * SubjectPublicKeyInfo. The laptop has no domain name and no CA-signed
 * certificate, and a release APK trusts only system anchors, so the pin carried
 * out of band in the pairing QR replaces chain building entirely: the platform
 * trust store is never consulted.
 */
class PinnedTrustManager(private val pin: String) : X509TrustManager {
    init {
        // An empty pin must never be readable as "trust everything". Callers
        // that do not pin build a plain client instead.
        require(pin.isNotBlank()) { "A certificate pin is required" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("PocketExit never authenticates clients by certificate")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server presented no certificate")
        val presented = PinnedTrust.fingerprint(leaf)
        if (!MessageDigest.isEqual(pin.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))) {
            throw CertificateException("Certificate pin mismatch: expected $pin, presented $presented")
        }
    }

    // The chain is never walked, so there are no issuers to advertise.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object PinnedTrust {
    /** `base64url_nopad(SHA-256(DER(SubjectPublicKeyInfo)))`, the pin the QR carries. */
    fun fingerprint(certificate: X509Certificate): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance(DIGEST).digest(certificate.publicKey.encoded))

    fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val context = SSLContext.getInstance(PROTOCOL)
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return context.socketFactory
    }

    /**
     * Pins [builder] when [pin] is set and leaves it untouched otherwise, so a
     * server-mode agent keeps validating against the platform trust store.
     * Hostname verification is disabled alongside the pin: the pin already
     * binds the connection to one exact key and the server has no verifiable
     * name. That pairing is safe only because an empty pin never reaches here.
     */
    fun applyPin(builder: OkHttpClient.Builder, pin: String): OkHttpClient.Builder {
        if (pin.isBlank()) return builder
        val trustManager = PinnedTrustManager(pin)
        return builder
            .sslSocketFactory(socketFactory(trustManager), trustManager)
            .hostnameVerifier { _, _ -> true }
    }

    /** A fresh builder, pinned when [pin] is set and plain when it is empty. */
    fun clientBuilder(pin: String): OkHttpClient.Builder = applyPin(OkHttpClient.Builder(), pin)

    private const val DIGEST = "SHA-256"
    private const val PROTOCOL = "TLS"
}
