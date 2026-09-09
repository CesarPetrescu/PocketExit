package com.photonspark.pocketexit.network

import android.content.Context
import android.net.Network
import com.photonspark.pocketexit.data.RuntimeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalUrlRequest
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CronetTransport(
    context: Context,
    private val serverUrl: String,
    private val pin: String = "",
) : AutoCloseable {
    data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val protocol: String,
    ) {
        fun bodyText(): String = body.toString(Charsets.UTF_8)
    }

    class RunningRequest internal constructor(
        private val cancellation: () -> Unit,
        val completion: CompletableDeferred<HttpResponse>,
    ) {
        fun cancel() = cancellation()
    }

    class HttpStatusException(
        val status: Int,
        val responseBody: String,
    ) : IOException("HTTP $status${responseBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")

    private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pocket-exit-cronet").apply { isDaemon = true }
    }
    private val uploadExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pocket-exit-upload").apply { isDaemon = true }
    }
    private val pinnedClients = ConcurrentHashMap<Long, OkHttpClient>()

    // Cronet builds a chain against the platform trust store and cannot be told
    // to trust a bare public key, so a pinned personal-mode server is served by
    // OkHttp with the pinned trust manager and the engine is never built.
    private val engine: CronetEngine? = if (pin.isNotBlank()) {
        null
    } else {
        val builder = CronetEngine.Builder(context.applicationContext)
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 2L * 1024L * 1024L)
            .setUserAgent("PocketExit/0.1 Android")

        runCatching {
            val uri = URI(serverUrl)
            val port = if (uri.port > 0) uri.port else 443
            val host = requireNotNull(uri.host) { "Server URL must have a host" }
            builder.addQuicHint(host, port, port)
        }
        builder.build()
    }

    suspend fun request(
        method: String,
        path: String,
        token: String,
        network: Network,
        body: ByteArray? = null,
        contentType: String = "application/json",
    ): HttpResponse {
        val running = start(
            method = method,
            path = path,
            token = token,
            network = network,
            body = body,
            source = null,
            contentType = contentType,
            responseConsumer = null,
        )
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                running.completion.await()
            }
        } finally {
            if (!running.completion.isCompleted) running.cancel()
        }
    }

    fun download(
        path: String,
        token: String,
        network: Network,
        onBytes: (ByteArray) -> Unit,
    ): RunningRequest = start(
        method = "GET",
        path = path,
        token = token,
        network = network,
        body = null,
        source = null,
        contentType = "application/octet-stream",
        responseConsumer = onBytes,
    )

    fun upload(
        path: String,
        token: String,
        network: Network,
        source: InputStream,
    ): RunningRequest = start(
        method = "POST",
        path = path,
        token = token,
        network = network,
        body = null,
        source = source,
        contentType = "application/octet-stream",
        responseConsumer = null,
    )

    private fun start(
        method: String,
        path: String,
        token: String,
        network: Network,
        body: ByteArray?,
        source: InputStream?,
        contentType: String,
        responseConsumer: ((ByteArray) -> Unit)?,
    ): RunningRequest {
        val url = serverUrl.trimEnd('/') + if (path.startsWith('/')) path else "/$path"
        val engine = this.engine ?: return startPinned(
            method = method,
            url = url,
            token = token,
            network = network,
            body = body,
            source = source,
            contentType = contentType,
            responseConsumer = responseConsumer,
        )
        val completion = CompletableDeferred<HttpResponse>()
        val callback = StreamingCallback(completion, responseConsumer)
        val uploadProvider: UploadDataProvider? = when {
            body != null -> ByteArrayProvider(body)
            source != null -> InputStreamProvider(source)
            else -> null
        }
        val builder = engine.newUrlRequestBuilder(url, callback, callbackExecutor)
            .setHttpMethod(method)
            .disableCache()
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json, application/octet-stream")

        if (uploadProvider != null) {
            builder.addHeader("Content-Type", contentType)
            builder.setUploadDataProvider(uploadProvider, uploadExecutor)
        }

        val experimental = builder as? ExperimentalUrlRequest.Builder
            ?: throw IllegalStateException("This Cronet provider cannot bind requests to Android Network objects")
        experimental.bindToNetwork(network.networkHandle)

        val request = experimental.build()
        request.start()
        return RunningRequest({ request.cancel() }, completion)
    }

    // The pinned path mirrors the Cronet one: the same headers, the same
    // per-Network binding, the same streaming semantics. It gives up HTTP/3,
    // which is the price of trusting a bare public key.
    private fun startPinned(
        method: String,
        url: String,
        token: String,
        network: Network,
        body: ByteArray?,
        source: InputStream?,
        contentType: String,
        responseConsumer: ((ByteArray) -> Unit)?,
    ): RunningRequest {
        val completion = CompletableDeferred<HttpResponse>()
        val mediaType = contentType.toMediaType()
        val verb = method.uppercase(Locale.US)
        val payload: RequestBody? = when {
            body != null -> body.toRequestBody(mediaType)
            source != null -> StreamBody(source, mediaType)
            // OkHttp rejects a bodyless POST or PUT, and Cronet allows one.
            verb == "GET" || verb == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(mediaType)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json, application/octet-stream")
            .header("Cache-Control", "no-store")
            .method(verb, payload)
            .build()
        val call = pinnedClient(network).newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                completion.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { deliver(it, responseConsumer, completion) }
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                }
            }
        })
        completion.invokeOnCompletion { error -> if (error != null) call.cancel() }
        return RunningRequest({ call.cancel() }, completion)
    }

    private fun deliver(
        response: Response,
        responseConsumer: ((ByteArray) -> Unit)?,
        completion: CompletableDeferred<HttpResponse>,
    ) {
        val status = response.code
        val protocol = response.protocol.toString()
        RuntimeStore.update { it.copy(negotiatedProtocol = protocol) }
        if (status !in 200..299) {
            val message = response.peekBody(MAX_BUFFERED_BODY_BYTES.toLong()).string()
            completion.completeExceptionally(HttpStatusException(status, message))
            return
        }
        if (responseConsumer == null) {
            val bytes = response.peekBody(MAX_BUFFERED_BODY_BYTES.toLong()).bytes()
            completion.complete(HttpResponse(status, bytes, protocol))
            return
        }
        val stream = response.body?.byteStream()
            ?: throw IOException("Server sent no response body")
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (read > 0) responseConsumer(chunk.copyOf(read))
        }
        completion.complete(HttpResponse(status, ByteArray(0), protocol))
    }

    // The control long-poll holds a response open for up to a minute, so the
    // read timeout is left off and callers bound the request themselves.
    private fun pinnedClient(network: Network): OkHttpClient =
        pinnedClients.computeIfAbsent(network.networkHandle) {
            PinnedTrust.applyPin(
                OkHttpClient.Builder()
                    .socketFactory(network.socketFactory)
                    .dns { hostname -> network.getAllByName(hostname).toList() }
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(0, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true),
                pin,
            ).build()
        }

    override fun close() {
        try {
            runCatching { engine?.shutdown() }
            pinnedClients.values.forEach { client ->
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            pinnedClients.clear()
        } finally {
            callbackExecutor.shutdownNow()
            uploadExecutor.shutdownNow()
        }
    }

    private class StreamingCallback(
        private val completion: CompletableDeferred<HttpResponse>,
        private val responseConsumer: ((ByteArray) -> Unit)?,
    ) : UrlRequest.Callback() {
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024)
        private val errorBody = ByteArrayOutputStream()
        private val completed = AtomicBoolean(false)
        private var status = 0
        private var protocol = "unknown"
        override fun onRedirectReceived(
            request: UrlRequest,
            info: UrlResponseInfo,
            newLocationUrl: String,
        ) {
            fail(IOException("Unexpected redirect to $newLocationUrl"))
            request.cancel()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            status = info.httpStatusCode
            protocol = info.negotiatedProtocol.ifBlank { "unknown" }
            RuntimeStore.update { it.copy(negotiatedProtocol = protocol) }
            buffer.clear()
            request.read(buffer)
        }

        override fun onReadCompleted(
            request: UrlRequest,
            info: UrlResponseInfo,
            byteBuffer: ByteBuffer,
        ) {
            try {
                byteBuffer.flip()
                if (byteBuffer.hasRemaining()) {
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    if (status in 200..299 && responseConsumer != null) {
                        responseConsumer.invoke(bytes)
                    } else {
                        if (errorBody.size() + bytes.size > MAX_BUFFERED_BODY_BYTES) {
                            throw IOException("HTTP response exceeded $MAX_BUFFERED_BODY_BYTES bytes")
                        }
                        errorBody.write(bytes)
                    }
                }
                byteBuffer.clear()
                request.read(byteBuffer)
            } catch (error: Throwable) {
                fail(error)
                request.cancel()
            }
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            val body = errorBody.toByteArray()
            if (status !in 200..299) {
                fail(HttpStatusException(status, body.toString(Charsets.UTF_8)))
            } else {
                succeed(HttpResponse(status, body, protocol))
            }
        }

        override fun onFailed(
            request: UrlRequest,
            info: UrlResponseInfo?,
            error: CronetException,
        ) = fail(error)

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            if (!completion.isCompleted) fail(IOException("Request canceled"))
        }

        private fun succeed(response: HttpResponse) {
            if (completed.compareAndSet(false, true)) completion.complete(response)
        }

        private fun fail(error: Throwable) {
            if (completed.compareAndSet(false, true)) completion.completeExceptionally(error)
        }
    }

    private class ByteArrayProvider(private val bytes: ByteArray) : UploadDataProvider() {
        private var offset = 0

        override fun getLength(): Long = bytes.size.toLong()

        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            try {
                val count = minOf(buffer.remaining(), bytes.size - offset)
                if (count > 0) {
                    buffer.put(bytes, offset, count)
                    offset += count
                }
                // getLength() makes this a non-chunked upload; Cronet requires false.
                sink.onReadSucceeded(false)
            } catch (error: Exception) {
                sink.onReadError(error)
            }
        }

        override fun rewind(sink: UploadDataSink) {
            offset = 0
            sink.onRewindSucceeded()
        }
    }

    private class InputStreamProvider(private val source: InputStream) : UploadDataProvider() {
        override fun getLength(): Long = -1L

        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            try {
                val temporary = ByteArray(minOf(buffer.remaining(), 64 * 1024))
                val count = source.read(temporary)
                if (count < 0) {
                    sink.onReadSucceeded(true)
                } else {
                    buffer.put(temporary, 0, count)
                    sink.onReadSucceeded(false)
                }
            } catch (error: Exception) {
                sink.onReadError(error)
            }
        }

        override fun rewind(sink: UploadDataSink) {
            sink.onRewindError(IOException("Streaming uploads cannot be rewound"))
        }

        override fun close() {
            // The circuit owns the socket/input stream and closes it during cancellation.
        }
    }

    private class StreamBody(
        private val source: InputStream,
        private val mediaType: MediaType,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        // Length is unknown, so OkHttp streams it chunked, as Cronet does.
        override fun contentLength(): Long = -1L

        override fun writeTo(sink: BufferedSink) {
            source.source().use(sink::writeAll)
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 70_000L
        private const val MAX_BUFFERED_BODY_BYTES = 1024 * 1024
    }
}
