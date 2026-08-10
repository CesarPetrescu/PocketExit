package com.photonspark.pocketexit.network

import android.net.Network
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class WebSocketTransport(private val serverUrl: String) : Closeable {
    class Session internal constructor(
        private val socket: WebSocket,
        private val channel: Channel<ByteArray>,
        private val completion: CompletableDeferred<Unit>,
    ) : Closeable {
        val incoming: ReceiveChannel<ByteArray> get() = channel

        suspend fun send(bytes: ByteArray) {
            while (socket.queueSize() > MAX_QUEUED_BYTES) delay(5)
            if (!socket.send(bytes.toByteString())) throw IOException("WebSocket is closed")
        }

        suspend fun awaitClosed() = completion.await()

        override fun close() {
            if (!socket.close(1000, "circuit closed")) socket.cancel()
            channel.close()
        }
    }

    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    suspend fun open(
        path: String,
        token: String,
        network: Network,
    ): Session {
        val opened = CompletableDeferred<Session>()
        val incoming = Channel<ByteArray>(INCOMING_CAPACITY)
        val completion = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        val socket = client(network).newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (response.header("Sec-WebSocket-Protocol") != SUBPROTOCOL) {
                        val error = IOException("Server rejected the PocketExit WebSocket protocol")
                        opened.completeExceptionally(error)
                        completion.completeExceptionally(error)
                        webSocket.cancel()
                        return
                    }
                    opened.complete(Session(webSocket, incoming, completion))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (incoming.trySend(bytes.toByteArray()).isFailure) {
                        val error = IOException("WebSocket receive queue overflow")
                        incoming.close(error)
                        completion.completeExceptionally(error)
                        webSocket.cancel()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.close()
                    completion.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val failure = IOException("WebSocket transport failed", t)
                    opened.completeExceptionally(failure)
                    incoming.close(failure)
                    completion.completeExceptionally(failure)
                }
            },
        )
        opened.invokeOnCompletion { error -> if (error != null) socket.cancel() }
        return opened.await()
    }

    override fun close() {
        clients.values.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        clients.clear()
    }

    private fun client(network: Network): OkHttpClient = clients.computeIfAbsent(network.networkHandle) {
        OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns { hostname -> network.getAllByName(hostname).toList() }
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        const val SUBPROTOCOL = "pocketexit.circuit.v1"
        private const val INCOMING_CAPACITY = 64
        private const val MAX_QUEUED_BYTES = 4L * 1024L * 1024L
    }
}
