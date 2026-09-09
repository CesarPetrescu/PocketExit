package com.photonspark.pocketexit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.AgentRuntime
import com.photonspark.pocketexit.data.NetworkKind
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
internal fun HomeScreen(
    config: AgentConfig,
    runtime: AgentRuntime,
    message: String,
    pairedNotice: Boolean,
    socks: SocksHint?,
    onToggle: () -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by rememberClock()
    val state = connectionState(runtime, now, config.controlAcceptsUnvalidatedWifi)

    Scaffold(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        containerColor = CanvasBlack,
        bottomBar = { ControlBar(state, onToggle) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTopBar(
                eyebrow = stringResource(R.string.home_eyebrow),
                title = stringResource(R.string.home_title),
                onAction = onOpenSettings,
                actionDescription = stringResource(R.string.home_settings),
            )
            if (message.isNotBlank()) MessageBanner(message)
            if (pairedNotice) PairedCard(config, socks, onDismissNotice)
            StatusCard(
                state = state,
                runtime = runtime,
                config = config,
                onReconnect = onReconnect,
                onOpenSettings = onOpenSettings,
                onOpenNetworkSettings = onOpenNetworkSettings,
            )
            TrafficCard(runtime)
            ExitPathCard(config, runtime)
            SessionCard(config, runtime)
        }
    }
}

/** One tick a second, so a stale heartbeat becomes visible without the service saying so. */
@Composable
private fun rememberClock(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

/**
 * The `socks` block the claim answered with: where the computer points its own
 * proxy settings. Display only, and it carries no password.
 */
internal data class SocksHint(val host: String, val port: Int, val username: String)

@Composable
private fun PairedCard(config: AgentConfig, socks: SocksHint?, onDismiss: () -> Unit) {
    SectionCard(container = PanelRaised) {
        Text(
            text = stringResource(R.string.home_paired_title, formatOrigin(config.normalizedServerUrl)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Signal,
        )
        Text(
            text = stringResource(R.string.home_paired_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        if (socks != null) {
            Text(
                text = stringResource(
                    R.string.home_paired_socks,
                    socks.host,
                    socks.port,
                    socks.username,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
            )
        }
        QuietButton(
            text = stringResource(R.string.home_paired_dismiss),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            contentColor = Muted,
        )
    }
}

@Composable
private fun StatusCard(
    state: ConnectionState,
    runtime: AgentRuntime,
    config: AgentConfig,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
) {
    val origin = formatOrigin(config.normalizedServerUrl)
    val title = when (state) {
        ConnectionState.STOPPED -> stringResource(R.string.state_stopped_title)
        ConnectionState.CONNECTING -> stringResource(R.string.state_connecting_title)
        ConnectionState.ONLINE -> stringResource(R.string.state_online_title)
        ConnectionState.DEGRADED -> stringResource(R.string.state_degraded_title)
        ConnectionState.NO_NETWORK -> stringResource(R.string.state_no_network_title)
    }
    val detail = when (state) {
        ConnectionState.STOPPED -> stringResource(R.string.state_stopped_detail)
        ConnectionState.CONNECTING -> stringResource(R.string.state_connecting_detail, origin)
        ConnectionState.ONLINE -> stringResource(R.string.state_online_detail)
        ConnectionState.DEGRADED -> stringResource(R.string.state_degraded_detail)
        ConnectionState.NO_NETWORK -> stringResource(R.string.state_no_network_detail)
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(state)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor(state),
            )
        }
        Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = Ink)
        if (state == ConnectionState.DEGRADED && runtime.lastError.isNotBlank()) {
            Text(
                text = stringResource(R.string.home_last_error, runtime.lastError),
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
            )
        }
        when (state) {
            ConnectionState.DEGRADED -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton(
                    text = stringResource(R.string.home_reconnect),
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f),
                )
                QuietButton(
                    text = stringResource(R.string.home_open_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                    contentColor = Muted,
                )
            }
            ConnectionState.NO_NETWORK -> QuietButton(
                text = stringResource(R.string.home_network_settings),
                onClick = onOpenNetworkSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Unit
        }
    }
}

private data class TrafficSample(val up: Long, val down: Long)

@Composable
private fun TrafficCard(runtime: AgentRuntime, modifier: Modifier = Modifier) {
    val currentRuntime by rememberUpdatedState(runtime)
    var samples by remember { mutableStateOf(List(WINDOW_SECONDS) { TrafficSample(0, 0) }) }

    LaunchedEffect(Unit) {
        var previousUp = currentRuntime.bytesUp
        var previousDown = currentRuntime.bytesDown
        while (true) {
            delay(1_000)
            val next = currentRuntime
            samples = samples.drop(1) + TrafficSample(
                up = (next.bytesUp - previousUp).coerceAtLeast(0),
                down = (next.bytesDown - previousDown).coerceAtLeast(0),
            )
            previousUp = next.bytesUp
            previousDown = next.bytesDown
        }
    }

    val latest = samples.last()
    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                CardTitle(stringResource(R.string.traffic_title))
                Text(
                    text = stringResource(R.string.traffic_total, formatRate(latest.down + latest.up)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Legend(stringResource(R.string.traffic_down), Download, formatRate(latest.down))
                Legend(stringResource(R.string.traffic_up), Upload, formatRate(latest.up))
            }
        }
        TrafficChart(
            samples = samples,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.traffic_window_start),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Text(
                text = stringResource(R.string.traffic_window_end),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun Legend(label: String, color: Color, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(
            text = "$label $value",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun TrafficChart(samples: List<TrafficSample>, modifier: Modifier = Modifier) {
    val latest = samples.last()
    val description = stringResource(
        R.string.traffic_chart_description,
        formatRate(latest.down),
        formatRate(latest.up),
    )
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val ceiling = max(1L, samples.maxOf { max(it.up, it.down) })
        repeat(3) { row ->
            val y = size.height * row / 2f
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f)
        }

        fun points(selector: (TrafficSample) -> Long): List<Offset> = samples.mapIndexed { index, sample ->
            Offset(
                x = size.width * index / (samples.size - 1).coerceAtLeast(1),
                y = size.height - (selector(sample).toFloat() / ceiling) * size.height * 0.88f,
            )
        }

        val downPoints = points { it.down }
        val upPoints = points { it.up }
        val area = smoothPath(downPoints).apply {
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(Download.copy(alpha = 0.28f), Color.Transparent)),
        )
        drawPath(
            smoothPath(downPoints),
            Download,
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            smoothPath(upPoints),
            Upload,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    points.zipWithNext().forEach { (from, to) ->
        val middle = (from.x + to.x) / 2f
        cubicTo(middle, from.y, middle, to.y, to.x, to.y)
    }
}

@Composable
private fun ExitPathCard(config: AgentConfig, runtime: AgentRuntime) {
    val kind = exitNetwork(runtime, config.exitPolicy)
    val snapshot = if (kind == NetworkKind.NONE) null else runtime.snapshotFor(kind)
    SectionCard {
        CardTitle(stringResource(R.string.exit_path_title))
        if (snapshot == null) {
            Text(
                text = stringResource(R.string.exit_path_none),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Warning,
            )
        } else {
            Text(
                text = kind.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                text = if (snapshot.metered) {
                    stringResource(R.string.exit_path_metered)
                } else {
                    stringResource(R.string.exit_path_unmetered)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (snapshot.metered) Warning else Signal,
            )
            if (snapshot.interfaceName.isNotBlank()) {
                InfoRow(
                    label = stringResource(R.string.exit_path_interface, snapshot.interfaceName),
                    value = stringResource(R.string.exit_path_downlink, formatKbps(snapshot.downKbps)),
                )
            }
        }
        InfoRow(
            label = stringResource(R.string.exit_path_control),
            value = runtime.activeControlNetwork.label,
        )
        InfoRow(
            label = stringResource(R.string.exit_path_policy),
            value = config.exitPolicy.label,
        )
    }
}

@Composable
private fun SessionCard(config: AgentConfig, runtime: AgentRuntime) {
    SectionCard {
        CardTitle(stringResource(R.string.details_title))
        InfoRow(
            label = stringResource(R.string.details_protocol),
            value = runtime.negotiatedProtocol.ifBlank { stringResource(R.string.value_none) },
        )
        InfoRow(
            label = stringResource(R.string.details_circuits),
            value = runtime.activeCircuits.toString(),
        )
        InfoRow(
            label = stringResource(R.string.details_heartbeat),
            value = if (runtime.lastHeartbeatEpochMs > 0) {
                formatClockTime(runtime.lastHeartbeatEpochMs)
            } else {
                stringResource(R.string.details_heartbeat_never)
            },
        )
        InfoRow(
            label = stringResource(R.string.details_server),
            value = formatOrigin(config.normalizedServerUrl),
        )
        InfoRow(
            label = stringResource(R.string.details_node),
            value = config.nodeId,
        )
        InfoRow(
            label = stringResource(R.string.details_trust),
            value = if (config.pin.isEmpty()) {
                stringResource(R.string.details_trust_platform)
            } else {
                stringResource(R.string.details_trust_pinned, shortPin(config.pin))
            },
        )
    }
}

/**
 * The one control that matters, pinned to the bottom of the screen where a
 * thumb reaches it, and labelled with the action rather than the state.
 */
@Composable
private fun ControlBar(state: ConnectionState, onToggle: () -> Unit) {
    val running = state != ConnectionState.STOPPED
    Surface(color = Panel, contentColor = Ink) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            PrimaryButton(
                text = if (running) {
                    stringResource(R.string.home_stop)
                } else {
                    stringResource(R.string.home_start)
                },
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                container = if (running) Danger else Download,
            )
        }
    }
}

private const val WINDOW_SECONDS = 36
