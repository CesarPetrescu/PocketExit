package com.photonspark.pocketexit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.photonspark.pocketexit.BuildConfig
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentCommand
import com.photonspark.pocketexit.data.AppPreferences
import com.photonspark.pocketexit.data.HeartbeatData
import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.RuntimeStore
import com.photonspark.pocketexit.network.CronetTransport
import com.photonspark.pocketexit.network.NetworkMonitor
import com.photonspark.pocketexit.network.WebSocketTransport
import com.photonspark.pocketexit.proxy.CircuitManager
import com.photonspark.pocketexit.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.min

class ExitNodeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var preferences: AppPreferences
    private lateinit var networkMonitor: NetworkMonitor
    private var agentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        networkMonitor = NetworkMonitor(this)
        networkMonitor.start()
        createNotificationChannel()

        serviceScope.launch {
            networkMonitor.inventory.collectLatest { inventory ->
                RuntimeStore.update {
                    it.copy(wifi = inventory.wifi, cellular = inventory.cellular)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                preferences.setEnabled(false)
                stopAgent("Stopped by user")
                return START_NOT_STICKY
            }
            ACTION_RESTART -> startForegroundNow("Restarting")
            else -> startForegroundNow("Starting")
        }

        val config = preferences.current
        if (!config.enabled) {
            stopAgent("Agent disabled")
            return START_NOT_STICKY
        }
        restartAgent()
        return START_STICKY
    }

    override fun onDestroy() {
        agentJob?.cancel()
        networkMonitor.stop()
        preferences.close()
        serviceScope.cancel()
        RuntimeStore.reset("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restartAgent() {
        val previous = agentJob
        agentJob = serviceScope.launch {
            previous?.cancelAndJoin()
            runAgent()
        }
    }

    private suspend fun runAgent() = coroutineScope {
        val initialConfig = preferences.current
        initialConfig.validationError()?.let { error ->
            RuntimeStore.update { it.copy(running = false, statusMessage = error, lastError = error) }
            updateNotification(error)
            stopSelf()
            return@coroutineScope
        }

        val transport = CronetTransport(this@ExitNodeService, initialConfig.normalizedServerUrl)
        val socketTransport = WebSocketTransport(initialConfig.normalizedServerUrl)
        val circuits = CircuitManager(this, preferences, networkMonitor, transport, socketTransport)
        RuntimeStore.update {
            it.copy(running = true, registered = false, statusMessage = "Connecting", lastError = "")
        }
        updateNotification("Connecting")

        try {
            launch { heartbeatLoop(transport, circuits) }
            controlLoop(transport, circuits)
        } finally {
            circuits.closeAll()
            socketTransport.close()
            transport.close()
            RuntimeStore.update {
                it.copy(
                    running = false,
                    registered = false,
                    activeControlNetwork = NetworkKind.NONE,
                    activeCircuits = 0,
                    statusMessage = if (preferences.current.enabled) "Disconnected" else "Stopped",
                )
            }
        }
    }

    private suspend fun heartbeatLoop(
        transport: CronetTransport,
        circuits: CircuitManager,
    ) {
        while (currentCoroutineContext().isActive && preferences.current.enabled) {
            sendHeartbeat(transport, circuits)
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private suspend fun sendHeartbeat(
        transport: CronetTransport,
        circuits: CircuitManager,
    ) {
        val config = preferences.current
        val control = networkMonitor.select(config.controlPolicy)
        if (control == null) {
            RuntimeStore.update {
                it.copy(
                    registered = false,
                    statusMessage = "Waiting for ${config.controlPolicy.label}",
                    activeControlNetwork = NetworkKind.NONE,
                )
            }
            updateNotification("Waiting for network")
            return
        }
        val inventory = networkMonitor.inventory.value
        val battery = batteryState()
        val heartbeat = HeartbeatData(
            config = config,
            activeControlNetwork = control.kind,
            wifi = inventory.wifi,
            cellular = inventory.cellular,
            batteryPercent = battery.first,
            charging = battery.second,
            activeCircuits = circuits.activeCount,
            bytesUp = circuits.bytesUp,
            bytesDown = circuits.bytesDown,
            transportProtocol = RuntimeStore.state.value.negotiatedProtocol,
        )

        try {
            transport.request(
                method = "POST",
                path = "/agent/v1/heartbeat",
                token = config.agentToken,
                network = control.network,
                body = heartbeat.toJson(BuildConfig.VERSION_NAME).toString().toByteArray(Charsets.UTF_8),
            )
            RuntimeStore.update {
                it.copy(
                    running = true,
                    registered = true,
                    statusMessage = "Online",
                    activeControlNetwork = control.kind,
                    activeCircuits = circuits.activeCount,
                    bytesUp = circuits.bytesUp,
                    bytesDown = circuits.bytesDown,
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                    lastError = "",
                )
            }
            updateNotification("${control.kind.label} control · ${circuits.activeCount} circuits")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            RuntimeStore.update {
                it.copy(
                    registered = false,
                    statusMessage = "Heartbeat failed",
                    activeControlNetwork = control.kind,
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
            updateNotification("Heartbeat failed")
        }
    }

    private suspend fun controlLoop(
        transport: CronetTransport,
        circuits: CircuitManager,
    ) {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive && preferences.current.enabled) {
            val config = preferences.current
            val control = networkMonitor.select(config.controlPolicy)
            if (control == null) {
                RuntimeStore.update {
                    it.copy(
                        statusMessage = "Waiting for ${config.controlPolicy.label}",
                        activeControlNetwork = NetworkKind.NONE,
                    )
                }
                delay(1_000)
                continue
            }

            RuntimeStore.update { it.copy(activeControlNetwork = control.kind) }
            try {
                val response = transport.request(
                    method = "GET",
                    path = "/agent/v1/control?node_id=${query(config.nodeId)}",
                    token = config.agentToken,
                    network = control.network,
                )
                if (response.status == 200 && response.body.isNotEmpty()) {
                    processCommand(AgentCommand.fromJson(JSONObject(response.bodyText())), circuits)
                }
                backoffMs = 1_000L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                RuntimeStore.update {
                    it.copy(
                        registered = false,
                        statusMessage = "Control reconnecting",
                        lastError = error.message ?: error.javaClass.simpleName,
                    )
                }
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, 15_000L)
            }
        }
    }

    private fun processCommand(command: AgentCommand, circuits: CircuitManager) {
        when (command.type) {
            "open_tcp", "open_udp" -> circuits.open(command)
            "close" -> circuits.close(command.circuitId)
            "policy_update" -> {
                preferences.applyRemotePolicies(command.controlPolicy, command.exitPolicy)
                RuntimeStore.update { it.copy(statusMessage = "Policies updated") }
            }
        }
    }

    private fun batteryState(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    private fun stopAgent(message: String) {
        agentJob?.cancel()
        agentJob = null
        RuntimeStore.reset(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun startForegroundNow(message: String) {
        val notification = notification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pocket Exit")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val ACTION_START = "com.photonspark.pocketexit.action.START"
        const val ACTION_RESTART = "com.photonspark.pocketexit.action.RESTART"
        const val ACTION_STOP = "com.photonspark.pocketexit.action.STOP"
        private const val NOTIFICATION_CHANNEL = "pocket_exit_agent"
        private const val NOTIFICATION_ID = 7301
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        fun start(context: Context, restart: Boolean = false) {
            val intent = Intent(context, ExitNodeService::class.java).setAction(
                if (restart) ACTION_RESTART else ACTION_START,
            )
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ExitNodeService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
