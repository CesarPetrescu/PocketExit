package com.photonspark.pocketexit.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Claims an agent token from a personal-mode server with the pairing code the
 * QR carried. This is the one request the agent makes before it has any
 * credentials, so it is also the first place the pin is exercised: a wrong key
 * on the far end fails here rather than silently at the first heartbeat.
 */
class PairingClient(
    private val serverUrl: String,
    private val pin: String,
) {
    sealed interface Result {
        data class Paired(
            val nodeId: String,
            val agentToken: String,
            val serverUrl: String,
            val socksHost: String,
            val socksPort: Int,
            val socksUsername: String,
        ) : Result

        /** `401`: no code is active, the code expired, or it did not match. */
        data class CodeRejected(val message: String) : Result

        /** `429`: too many claim attempts from this address. */
        data class RateLimited(val message: String) : Result

        /** `400`: the server would not accept the request as sent. */
        data class RequestRejected(val message: String) : Result

        /** No usable answer: transport failure, pin mismatch, or an unexpected status. */
        data class Unreachable(val message: String) : Result
    }

    suspend fun claim(
        code: String,
        deviceName: String,
        nodeId: String = "",
    ): Result = withContext(Dispatchers.IO) {
        val client = PinnedTrust.clientBuilder(pin)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        try {
            send(client, code, deviceName, nodeId)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun send(
        client: OkHttpClient,
        code: String,
        deviceName: String,
        nodeId: String,
    ): Result {
        val payload = JSONObject()
            .put("code", code)
            .put("device_name", deviceName)
        // node_id is a hint: the server sanitises it and assigns its own when
        // it is missing or already taken.
        if (nodeId.isNotBlank()) payload.put("node_id", nodeId)
        val request = Request.Builder()
            .url(origin() + CLAIM_PATH)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.peekBody(MAX_BODY_BYTES).string()
                when (response.code) {
                    200 -> paired(body)
                    400 -> Result.RequestRejected(
                        detailed("The server rejected the pairing request.", body),
                    )
                    401 -> Result.CodeRejected(
                        detailed(
                            "That pairing code was not accepted. Mint a fresh code on the " +
                                "server and scan it again.",
                            body,
                        ),
                    )
                    429 -> Result.RateLimited(
                        detailed(
                            "Too many pairing attempts. Wait ten minutes and try again.",
                            body,
                        ),
                    )
                    else -> Result.Unreachable(
                        detailed("The server answered HTTP ${response.code}.", body),
                    )
                }
            }
        } catch (error: IOException) {
            // A pin mismatch arrives as a handshake failure wrapping the
            // CertificateException, so the innermost message is the diagnosis.
            Result.Unreachable("Could not reach the server: ${rootMessage(error)}")
        }
    }

    private fun paired(body: String): Result {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result.Unreachable("The server sent a malformed pairing response")
        val nodeId = json.optString("node_id").trim()
        val agentToken = json.optString("agent_token").trim()
        if (nodeId.isEmpty() || agentToken.isEmpty()) {
            return Result.Unreachable("The server sent an incomplete pairing response")
        }
        val socks = json.optJSONObject("socks")
        return Result.Paired(
            nodeId = nodeId,
            agentToken = agentToken,
            serverUrl = json.optString("server_url").trim().trimEnd('/').ifEmpty { origin() },
            socksHost = socks?.optString("host").orEmpty(),
            socksPort = socks?.optInt("port") ?: 0,
            socksUsername = socks?.optString("username").orEmpty(),
        )
    }

    private fun origin(): String = serverUrl.trim().trimEnd('/')

    private fun detailed(message: String, body: String): String {
        val detail = runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            .orEmpty()
            .trim()
        return if (detail.isEmpty()) message else "$message ($detail)"
    }

    private fun rootMessage(error: Throwable): String {
        var cause: Throwable? = error
        var message = ""
        while (cause != null) {
            cause.message?.takeIf(String::isNotBlank)?.let { message = it }
            cause = cause.cause
        }
        return message.ifEmpty { error.javaClass.simpleName }
    }

    companion object {
        const val CLAIM_PATH = "/pair/v1/claim"
        private const val TIMEOUT_SECONDS = 15L
        private const val MAX_BODY_BYTES = 64L * 1024L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
