package com.photonspark.pocketexit.network

import android.net.Network
import com.photonspark.pocketexit.data.AgentConfig
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
 *
 * [network] is the Android network the claim leaves over, as every other agent
 * request already does. A personal-mode laptop answers on the LAN, so a claim
 * left to the default route would go out over cellular and never arrive. Null
 * means there is no better choice than the default route.
 */
class PairingClient(
    private val serverUrl: String,
    private val pin: String,
    private val network: Network? = null,
) {
    /**
     * Why a claim produced no token. The reason is typed rather than worded
     * here: every string a person reads is a resource, resolved by the
     * composable that shows it.
     */
    enum class Failure {
        /** `401`: no code is active, the code expired, or it did not match. */
        CODE_REJECTED,

        /** `429`: too many claim attempts from this address. */
        RATE_LIMITED,

        /** `400`: the server would not accept the request as sent. */
        REQUEST_REJECTED,

        /** Any other status. The status itself is on the result. */
        UNEXPECTED_STATUS,

        /** No answer at all: transport failure, or a pin mismatch. */
        UNREACHABLE,

        /** `200`, with a body this agent cannot use. */
        MALFORMED_RESPONSE,
    }

    sealed interface Result {
        data class Paired(
            val nodeId: String,
            val agentToken: String,
            val serverUrl: String,
            val socksHost: String,
            val socksPort: Int,
            val socksUsername: String,
        ) : Result

        data class Failed(
            val reason: Failure,
            /** The server's own `error` field, or the transport's message. May be empty. */
            val detail: String = "",
            /** The HTTP status, for [Failure.UNEXPECTED_STATUS]. */
            val status: Int = 0,
        ) : Result
    }

    suspend fun claim(
        code: String,
        deviceName: String,
        nodeId: String = "",
    ): Result = withContext(Dispatchers.IO) {
        val builder = PinnedTrust.clientBuilder(pin)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        // The pin composes with the per-network binding rather than replacing
        // it, exactly as the circuit and control clients do.
        if (network != null) {
            builder.socketFactory(network.socketFactory)
                .dns { hostname -> network.getAllByName(hostname).toList() }
        }
        val client = builder.build()
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
                    400 -> Result.Failed(Failure.REQUEST_REJECTED, serverDetail(body))
                    401 -> Result.Failed(Failure.CODE_REJECTED, serverDetail(body))
                    429 -> Result.Failed(Failure.RATE_LIMITED, serverDetail(body))
                    else -> Result.Failed(
                        Failure.UNEXPECTED_STATUS,
                        serverDetail(body),
                        response.code,
                    )
                }
            }
        } catch (error: IOException) {
            // A pin mismatch arrives as a handshake failure wrapping the
            // CertificateException, so the innermost message is the diagnosis.
            Result.Failed(Failure.UNREACHABLE, rootMessage(error))
        }
    }

    private fun paired(body: String): Result {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result.Failed(Failure.MALFORMED_RESPONSE)
        val nodeId = json.optString("node_id").trim()
        val agentToken = json.optString("agent_token").trim()
        // The assigned id is stored exactly as it arrived, so an id this phone
        // cannot store verbatim is a bad response rather than something to
        // quietly rewrite into a name the registry has never heard of.
        if (agentToken.isEmpty() || !AgentConfig.isValidNodeId(nodeId)) {
            return Result.Failed(Failure.MALFORMED_RESPONSE)
        }
        val socks = json.optJSONObject("socks")
        return Result.Paired(
            nodeId = nodeId,
            agentToken = agentToken,
            serverUrl = json.optString("server_url").trim().trimEnd('/').ifEmpty { origin() },
            socksHost = socks?.optString("host").orEmpty().trim(),
            socksPort = socks?.optInt("port") ?: 0,
            socksUsername = socks?.optString("username").orEmpty().trim(),
        )
    }

    private fun origin(): String = serverUrl.trim().trimEnd('/')

    private fun serverDetail(body: String): String =
        runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            .orEmpty()
            .trim()

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
