package com.photonspark.pocketexit.proxy

import android.net.Network
import com.photonspark.pocketexit.data.AgentCommand
import com.photonspark.pocketexit.data.AppPreferences
import com.photonspark.pocketexit.data.RuntimeStore
import com.photonspark.pocketexit.network.CronetTransport
import com.photonspark.pocketexit.network.DestinationAcl
import com.photonspark.pocketexit.network.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CircuitManager(
    private val scope: CoroutineScope,
    private val preferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val transport: CronetTransport,
) {
    private data class AgentIdentity(
        val nodeId: String,
        val token: String,
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val uploaded = AtomicLong(0)
    private val downloaded = AtomicLong(0)

    val activeCount: Int get() = jobs.size
    val bytesUp: Long get() = uploaded.get()
    val bytesDown: Long get() = downloaded.get()

    fun open(command: AgentCommand) {
        require(CIRCUIT_ID_REGEX.matches(command.circuitId)) { "Invalid circuit ID" }
        require(command.targetHost.isNotBlank()) { "Target host is required" }
        require(command.targetPort in 1..65_535) { "Invalid target port" }
        val config = preferences.current
        require(config.nodeId.isNotBlank() && config.agentToken.isNotBlank()) {
            "Agent identity is not configured"
        }
        val identity = AgentIdentity(config.nodeId, config.agentToken)

        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCircuit(command, identity)
        }
        val previous = jobs.putIfAbsent(command.circuitId, job)
        if (previous != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            jobs.remove(command.circuitId, job)
            publishCounters()
        }
        job.start()
        publishCounters()
    }

    fun close(circuitId: String) {
        if (!CIRCUIT_ID_REGEX.matches(circuitId)) return
        jobs.remove(circuitId)?.cancel()
        publishCounters()
    }

    suspend fun closeAll() {
        val snapshot = jobs.values.toList()
        jobs.clear()
        snapshot.forEach { it.cancel() }
        snapshot.forEach { runCatching { it.cancelAndJoin() } }
        publishCounters()
    }

    private suspend fun runCircuit(command: AgentCommand, identity: AgentIdentity) {
        var opened = false
        var terminalStatus: String? = null
        var terminalError = ""
        try {
            when (command.type) {
                "open_tcp" -> runTcp(command, identity) { opened = true }
                "open_udp" -> runUdp(command, identity) { opened = true }
                else -> throw IllegalArgumentException("Unsupported circuit command ${command.type}")
            }
        } catch (cancelled: CancellationException) {
            if (opened) terminalStatus = "closed"
            throw cancelled
        } catch (error: Throwable) {
            terminalStatus = "failed"
            terminalError = error.message ?: error.javaClass.simpleName
            RuntimeStore.update {
                it.copy(lastError = "${command.circuitId.take(8)}: $terminalError")
            }
        } finally {
            if (terminalStatus == null && opened) terminalStatus = "closed"
            terminalStatus?.let { status ->
                withContext(NonCancellable) {
                    postStatus(
                        circuitId = command.circuitId,
                        status = status,
                        error = terminalError,
                        identity = identity,
                        required = false,
                    )
                }
            }
        }
    }

    private suspend fun runTcp(
        command: AgentCommand,
        identity: AgentIdentity,
        onOpened: () -> Unit,
    ) {
        val exit = exitNetwork(command)
        val addresses = withContext(Dispatchers.IO) {
            DestinationAcl.resolve(exit.network, command.targetHost, command.allowPrivate)
        }
        val socket = connectTcp(exit.network, addresses, command.targetPort)
        var down: CronetTransport.RunningRequest? = null
        var up: CronetTransport.RunningRequest? = null
        try {
            val control = controlNetwork()
            val path = circuitPath(command.circuitId)
            val downRequest = transport.download(
                path = "$path/down?node_id=${query(identity.nodeId)}",
                token = identity.token,
                network = control.network,
            ) { bytes ->
                socket.getOutputStream().write(bytes)
                downloaded.addAndGet(bytes.size.toLong())
                publishCounters()
            }
            down = downRequest
            val countedInput = CountingInputStream(socket.getInputStream()) { count ->
                uploaded.addAndGet(count.toLong())
                publishCounters()
            }
            val upRequest = transport.upload(
                path = "$path/up?node_id=${query(identity.nodeId)}",
                token = identity.token,
                network = control.network,
                source = countedInput,
            )
            up = upRequest

            postStatus(
                circuitId = command.circuitId,
                status = "connected",
                error = "",
                identity = identity,
                selectedControl = control,
                required = true,
            )
            onOpened()

            awaitEither(downRequest, upRequest)
        } finally {
            runCatching { socket.close() }
            down?.cancel()
            up?.cancel()
        }
    }

    private suspend fun runUdp(
        command: AgentCommand,
        identity: AgentIdentity,
        onOpened: () -> Unit,
    ) {
        val exit = exitNetwork(command)
        val address = withContext(Dispatchers.IO) {
            DestinationAcl.resolve(exit.network, command.targetHost, command.allowPrivate).first()
        }
        val socket = DatagramSocket(null)
        var down: CronetTransport.RunningRequest? = null
        var up: CronetTransport.RunningRequest? = null
        try {
            exit.network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, command.targetPort))
            val control = controlNetwork()
            val decoder = DatagramCodec.Decoder()
            val path = circuitPath(command.circuitId)

            val downRequest = transport.download(
                path = "$path/down?node_id=${query(identity.nodeId)}",
                token = identity.token,
                network = control.network,
            ) { bytes ->
                downloaded.addAndGet(bytes.size.toLong())
                decoder.feed(bytes).forEach { payload ->
                    socket.send(DatagramPacket(payload, payload.size))
                }
                publishCounters()
            }
            down = downRequest
            val upRequest = transport.upload(
                path = "$path/up?node_id=${query(identity.nodeId)}",
                token = identity.token,
                network = control.network,
                source = DatagramFrameInputStream(socket) { count ->
                    uploaded.addAndGet(count.toLong())
                    publishCounters()
                },
            )
            up = upRequest

            postStatus(
                circuitId = command.circuitId,
                status = "connected",
                error = "",
                identity = identity,
                selectedControl = control,
                required = true,
            )
            onOpened()

            awaitEither(downRequest, upRequest)
        } finally {
            socket.close()
            down?.cancel()
            up?.cancel()
        }
    }

    private suspend fun awaitEither(
        down: CronetTransport.RunningRequest,
        up: CronetTransport.RunningRequest,
    ) = coroutineScope {
        val downWait = async { down.completion.await() }
        val upWait = async { up.completion.await() }
        try {
            select<Unit> {
                downWait.onAwait { Unit }
                upWait.onAwait { Unit }
            }
        } finally {
            downWait.cancel()
            upWait.cancel()
        }
    }

    private fun connectTcp(network: Network, addresses: List<InetAddress>, port: Int): Socket {
        var lastError: Throwable? = null
        for (address in addresses) {
            val socket = network.socketFactory.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.sendBufferSize = maxOf(socket.sendBufferSize, 256 * 1024)
                socket.receiveBufferSize = maxOf(socket.receiveBufferSize, 256 * 1024)
                socket.connect(InetSocketAddress(address, port), 10_000)
                return socket
            } catch (error: Throwable) {
                lastError = error
                runCatching { socket.close() }
            }
        }
        throw IOException("Could not connect to destination", lastError)
    }

    private fun exitNetwork(command: AgentCommand): NetworkMonitor.BoundNetwork {
        val policy = command.exitPolicy ?: preferences.current.exitPolicy
        return networkMonitor.select(policy)
            ?: throw IOException("No validated network satisfies exit policy ${policy.wire}")
    }

    private fun controlNetwork(): NetworkMonitor.BoundNetwork {
        val policy = preferences.current.controlPolicy
        return networkMonitor.select(policy)
            ?: throw IOException("No validated network satisfies control policy ${policy.wire}")
    }

    private suspend fun postStatus(
        circuitId: String,
        status: String,
        error: String,
        identity: AgentIdentity,
        selectedControl: NetworkMonitor.BoundNetwork? = null,
        required: Boolean,
    ) {
        if (identity.token.isBlank()) {
            if (required) throw IOException("Agent token is missing")
            return
        }
        val control = selectedControl ?: networkMonitor.select(preferences.current.controlPolicy)
        if (control == null) {
            if (required) throw IOException("No control network is available")
            return
        }
        val body = JSONObject()
            .put("node_id", identity.nodeId)
            .put("status", status)
            .put("error", error.take(512))
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            withTimeout(STATUS_TIMEOUT_MS) {
                transport.request(
                    method = "POST",
                    path = "${circuitPath(circuitId)}/status",
                    token = identity.token,
                    network = control.network,
                    body = body,
                )
            }
        } catch (cancelled: CancellationException) {
            if (required) throw cancelled
        } catch (error: Exception) {
            if (required) throw error
        }
    }

    private fun publishCounters() {
        RuntimeStore.update {
            it.copy(
                activeCircuits = activeCount,
                bytesUp = uploaded.get(),
                bytesDown = downloaded.get(),
            )
        }
    }

    private fun circuitPath(circuitId: String): String {
        require(CIRCUIT_ID_REGEX.matches(circuitId)) { "Invalid circuit ID" }
        return "/agent/v1/circuits/$circuitId"
    }

    private fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private class CountingInputStream(
        source: InputStream,
        private val onRead: (Int) -> Unit,
    ) : FilterInputStream(source) {
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) onRead(it) }
    }

    private class DatagramFrameInputStream(
        private val socket: DatagramSocket,
        private val onFrame: (Int) -> Unit,
    ) : InputStream() {
        private var frame = ByteArray(0)
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset >= frame.size) receiveFrame()
            val count = minOf(length, frame.size - offset)
            frame.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }

        private fun receiveFrame() {
            val payload = ByteArray(DatagramCodec.MAX_DATAGRAM_SIZE)
            val packet = DatagramPacket(payload, payload.size)
            try {
                socket.receive(packet)
            } catch (error: SocketException) {
                throw IOException("UDP socket closed", error)
            }
            frame = DatagramCodec.frame(payload.copyOf(packet.length))
            offset = 0
            onFrame(frame.size)
        }
    }

    companion object {
        private val CIRCUIT_ID_REGEX = Regex("[a-f0-9]{32}")
        private const val STATUS_TIMEOUT_MS = 15_000L
    }
}
