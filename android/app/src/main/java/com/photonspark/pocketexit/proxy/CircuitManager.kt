package com.photonspark.pocketexit.proxy

import android.net.Network
import com.photonspark.pocketexit.data.AgentCommand
import com.photonspark.pocketexit.data.AppPreferences
import com.photonspark.pocketexit.data.RuntimeStore
import com.photonspark.pocketexit.network.CronetTransport
import com.photonspark.pocketexit.network.DestinationAcl
import com.photonspark.pocketexit.network.NetworkMonitor
import com.photonspark.pocketexit.network.WebSocketTransport
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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CircuitManager(
    private val scope: CoroutineScope,
    private val preferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val transport: CronetTransport,
    private val socketTransport: WebSocketTransport,
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
        var session: WebSocketTransport.Session? = null
        try {
            val control = controlNetwork()
            session = socketTransport.open(
                path = "${circuitPath(command.circuitId)}/ws?node_id=${query(identity.nodeId)}",
                token = identity.token,
                network = control.network,
            )

            postStatus(
                circuitId = command.circuitId,
                status = "connected",
                error = "",
                identity = identity,
                selectedControl = control,
                required = true,
            )
            onOpened()
            relayTcp(socket, session)
        } finally {
            runCatching { socket.close() }
            session?.close()
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
        var session: WebSocketTransport.Session? = null
        try {
            exit.network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, command.targetPort))
            val control = controlNetwork()
            session = socketTransport.open(
                path = "${circuitPath(command.circuitId)}/ws?node_id=${query(identity.nodeId)}",
                token = identity.token,
                network = control.network,
            )

            postStatus(
                circuitId = command.circuitId,
                status = "connected",
                error = "",
                identity = identity,
                selectedControl = control,
                required = true,
            )
            onOpened()
            relayUdp(socket, session)
        } finally {
            socket.close()
            session?.close()
        }
    }

    private suspend fun relayTcp(socket: Socket, session: WebSocketTransport.Session) = coroutineScope {
        val downWait = async(Dispatchers.IO) {
            for (bytes in session.incoming) {
                socket.getOutputStream().write(bytes)
                downloaded.addAndGet(bytes.size.toLong())
                publishCounters()
            }
        }
        val upWait = async(Dispatchers.IO) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = socket.getInputStream().read(buffer)
                if (count < 0) break
                session.send(buffer.copyOf(count))
                uploaded.addAndGet(count.toLong())
                publishCounters()
            }
        }
        val closedWait = async { session.awaitClosed() }
        try {
            select<Unit> {
                downWait.onAwait { Unit }
                upWait.onAwait { Unit }
                closedWait.onAwait { Unit }
            }
        } finally {
            runCatching { socket.close() }
            downWait.cancel()
            upWait.cancel()
            closedWait.cancel()
        }
    }

    private suspend fun relayUdp(socket: DatagramSocket, session: WebSocketTransport.Session) = coroutineScope {
        val decoder = DatagramCodec.Decoder()
        val downWait = async(Dispatchers.IO) {
            for (bytes in session.incoming) {
                downloaded.addAndGet(bytes.size.toLong())
                decoder.feed(bytes).forEach { payload ->
                    socket.send(DatagramPacket(payload, payload.size))
                }
                publishCounters()
            }
        }
        val upWait = async(Dispatchers.IO) {
            val payload = ByteArray(DatagramCodec.MAX_DATAGRAM_SIZE)
            while (true) {
                val packet = DatagramPacket(payload, payload.size)
                socket.receive(packet)
                val frame = DatagramCodec.frame(payload.copyOf(packet.length))
                session.send(frame)
                uploaded.addAndGet(frame.size.toLong())
                publishCounters()
            }
        }
        val closedWait = async { session.awaitClosed() }
        try {
            select<Unit> {
                downWait.onAwait { Unit }
                upWait.onAwait { Unit }
                closedWait.onAwait { Unit }
            }
        } finally {
            socket.close()
            downWait.cancel()
            upWait.cancel()
            closedWait.cancel()
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

    companion object {
        private val CIRCUIT_ID_REGEX = Regex("[a-f0-9]{32}")
        private const val STATUS_TIMEOUT_MS = 15_000L
    }
}
