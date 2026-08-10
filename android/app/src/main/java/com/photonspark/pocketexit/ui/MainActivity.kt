package com.photonspark.pocketexit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.AgentRuntime
import com.photonspark.pocketexit.data.AppPreferences
import com.photonspark.pocketexit.data.NetworkSnapshot
import com.photonspark.pocketexit.data.Policy
import com.photonspark.pocketexit.data.RuntimeStore
import com.photonspark.pocketexit.service.ExitNodeService
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

private val Ink = Color(0xFFF8FAFC)
private val Muted = Color(0xFF94A3B8)
private val CanvasBlack = Color(0xFF020617)
private val Panel = Color(0xFF0F172A)
private val PanelRaised = Color(0xFF172033)
private val Signal = Color(0xFF22C55E)
private val Download = Color(0xFF38BDF8)
private val Upload = Color(0xFFA78BFA)
private val Warning = Color(0xFFFBBF24)

class MainActivity : ComponentActivity() {
    private lateinit var preferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        setContent {
            PocketExitTheme {
                NotificationPermission()
                PocketExitScreen(preferences)
            }
        }
    }

    override fun onDestroy() {
        preferences.close()
        super.onDestroy()
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class AppPage { OVERVIEW, SETTINGS }

@Composable
private fun PocketExitScreen(preferences: AppPreferences) {
    val savedConfig by preferences.state.collectAsState()
    val runtime by RuntimeStore.state.collectAsState()
    var form by remember { mutableStateOf(savedConfig) }
    var message by remember { mutableStateOf("") }
    var pageName by rememberSaveable { mutableStateOf(AppPage.OVERVIEW.name) }
    val context = LocalContext.current

    LaunchedEffect(savedConfig) { form = savedConfig }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            delay(3_500)
            message = ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomTabs(
                selected = AppPage.valueOf(pageName),
                onSelected = { pageName = it.name },
            )
        },
    ) { contentPadding ->
        when (AppPage.valueOf(pageName)) {
            AppPage.OVERVIEW -> Overview(
                runtime = runtime,
                modifier = Modifier.padding(contentPadding),
                onToggle = {
                    if (runtime.running) {
                        val disabled = form.copy(enabled = false)
                        preferences.save(disabled)
                        form = disabled
                        ExitNodeService.stop(context)
                        message = "Stopping agent"
                    } else {
                        val enabled = form.copy(enabled = true)
                        val error = enabled.validationError()
                        if (error == null) {
                            preferences.save(enabled)
                            form = enabled
                            ExitNodeService.start(context, restart = false)
                            message = "Starting agent"
                        } else {
                            message = error
                            pageName = AppPage.SETTINGS.name
                        }
                    }
                },
            )

            AppPage.SETTINGS -> Settings(
                form = form,
                runtime = runtime,
                message = message,
                modifier = Modifier.padding(contentPadding),
                onFormChanged = { form = it },
                onSave = {
                    val updated = form.copy(enabled = runtime.running)
                    val error = updated.validationError()
                    if (error == null) {
                        preferences.save(updated)
                        form = updated
                        if (runtime.running) ExitNodeService.start(context, restart = true)
                        message = if (runtime.running) "Saved · transport restarting" else "Configuration saved"
                    } else {
                        message = error
                    }
                },
            )
        }
    }
}

@Composable
private fun Overview(runtime: AgentRuntime, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(runtime, onToggle)
        TrafficCard(runtime, Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NetworkCard("Wi-Fi", runtime.wifi, Modifier.weight(1f))
            NetworkCard("Cellular", runtime.cellular, Modifier.weight(1f))
        }
        TransportStrip(runtime)
    }
}

@Composable
private fun Header(runtime: AgentRuntime, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("POCKET EXIT", style = MaterialTheme.typography.labelMedium, color = Download)
            Text("Network pulse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(runtime)
            Button(onClick = onToggle) { Text(if (runtime.running) "Stop" else "Start") }
        }
    }
}

@Composable
private fun StatusPill(runtime: AgentRuntime) {
    val color = when {
        runtime.registered -> Signal
        runtime.running -> Warning
        else -> Muted
    }
    Row(
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(10.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(
            when {
                runtime.registered -> "Online"
                runtime.running -> "Linking"
                else -> "Offline"
            },
            color = color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private data class TrafficSample(val up: Long, val down: Long)

@Composable
private fun TrafficCard(runtime: AgentRuntime, modifier: Modifier = Modifier) {
    val currentRuntime by rememberUpdatedState(runtime)
    var samples by remember { mutableStateOf(List(36) { TrafficSample(0, 0) }) }

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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("LIVE TRAFFIC", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(
                        "${formatRate(latest.down + latest.up)} total",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Legend("DOWN", Download, formatRate(latest.down))
                    Legend("UP", Upload, formatRate(latest.up))
                }
            }
            TrafficChart(samples, Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("36 seconds ago", style = MaterialTheme.typography.labelSmall, color = Muted)
                Text("Now", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text("$label  $value", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun TrafficChart(samples: List<TrafficSample>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.semantics {
            contentDescription = "Live upload and download traffic over the last 36 seconds"
        },
    ) {
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
private fun NetworkCard(name: String, network: NetworkSnapshot, modifier: Modifier = Modifier) {
    val stateColor = when {
        network.validated -> Signal
        network.available -> Warning
        else -> Muted
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Bold)
                Box(Modifier.size(8.dp).background(stateColor, CircleShape))
            }
            Text(
                when {
                    network.validated -> "Internet ready"
                    network.available -> "Not validated"
                    else -> "Unavailable"
                },
                style = MaterialTheme.typography.bodySmall,
                color = stateColor,
            )
            Text(network.interfaceName.ifBlank { "No interface" }, style = MaterialTheme.typography.labelMedium)
            Text(
                "${formatKbps(network.downKbps)} down",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun TransportStrip(runtime: AgentRuntime) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelRaised),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Metric("CONTROL", runtime.activeControlNetwork.label)
            Metric("PROTOCOL", runtime.negotiatedProtocol.ifBlank { "—" })
            Metric("CIRCUITS", runtime.activeCircuits.toString())
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Settings(
    form: AgentConfig,
    runtime: AgentRuntime,
    message: String,
    modifier: Modifier = Modifier,
    onFormChanged: (AgentConfig) -> Unit,
    onSave: () -> Unit,
) {
    var connectionOpen by rememberSaveable { mutableStateOf(false) }
    var routingOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("POCKET EXIT", style = MaterialTheme.typography.labelMedium, color = Download)
        Text("Agent settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Connection secrets stay encrypted by Android Keystore.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )

        SettingsCard(
            title = "Connection",
            value = form.deviceName.ifBlank { "Not configured" },
            detail = form.serverUrl.ifBlank { "Backend URL, node identity and token" },
            onClick = { connectionOpen = true },
        )
        SettingsCard(
            title = "Network routing",
            value = "${form.controlPolicy.label} · ${form.exitPolicy.label}",
            detail = if (form.autoStart) "Starts after reboot" else "Manual start",
            onClick = { routingOpen = true },
        )
        SettingsCard(
            title = "Current session",
            value = runtime.statusMessage,
            detail = heartbeatText(runtime),
            onClick = {},
            enabled = false,
        )
        Spacer(Modifier.weight(1f))
        if (runtime.lastError.isNotBlank()) {
            Text(runtime.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) Text(message, color = Download, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (runtime.running) "Save and restart transport" else "Save settings")
        }
    }

    if (connectionOpen) {
        ConnectionDialog(
            form = form,
            onChanged = onFormChanged,
            onDismiss = { connectionOpen = false },
        )
    }
    if (routingOpen) {
        RoutingDialog(
            form = form,
            onChanged = onFormChanged,
            onDismiss = { routingOpen = false },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    value: String,
    detail: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = Muted)
                Text(value, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
            }
            if (enabled) {
                Spacer(Modifier.width(12.dp))
                Text("Edit", color = Download, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ConnectionDialog(form: AgentConfig, onChanged: (AgentConfig) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.serverUrl,
                    onValueChange = { onChanged(form.copy(serverUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backend URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.nodeId,
                    onValueChange = { onChanged(form.copy(nodeId = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Node ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.deviceName,
                    onValueChange = { onChanged(form.copy(deviceName = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.agentToken,
                    onValueChange = { onChanged(form.copy(agentToken = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Agent token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun RoutingDialog(form: AgentConfig, onChanged: (AgentConfig) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network routing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PolicyPicker("Control tunnel", form.controlPolicy) {
                    onChanged(form.copy(controlPolicy = it))
                }
                PolicyPicker("Proxy exit", form.exitPolicy) {
                    onChanged(form.copy(exitPolicy = it))
                }
                ToggleRow(
                    label = "Start after reboot",
                    detail = "Only when the agent is enabled",
                    checked = form.autoStart,
                    onChecked = { onChanged(form.copy(autoStart = it)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PolicyPicker(label: String, selected: Policy, onSelected: (Policy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Policy.entries.forEach { policy ->
                    DropdownMenuItem(
                        text = { Text(policy.label) },
                        onClick = {
                            expanded = false
                            onSelected(policy)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun BottomTabs(selected: AppPage, onSelected: (AppPage) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppPage.entries.forEach { page ->
            val active = page == selected
            TextButton(
                onClick = { onSelected(page) },
                modifier = Modifier.weight(1f).background(
                    if (active) Download.copy(alpha = 0.12f) else Color.Transparent,
                    RoundedCornerShape(12.dp),
                ),
            ) {
                Text(
                    if (page == AppPage.OVERVIEW) "Overview" else "Settings",
                    color = if (active) Download else Muted,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PocketExitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Download,
            onPrimary = CanvasBlack,
            secondary = Upload,
            tertiary = Signal,
            background = CanvasBlack,
            onBackground = Ink,
            surface = Panel,
            onSurface = Ink,
            surfaceVariant = PanelRaised,
            onSurfaceVariant = Muted,
            error = Color(0xFFFB7185),
        ),
        content = content,
    )
}

private fun heartbeatText(runtime: AgentRuntime): String = if (runtime.lastHeartbeatEpochMs > 0) {
    "Last heartbeat ${DateFormat.getTimeInstance().format(Date(runtime.lastHeartbeatEpochMs))}"
} else {
    "No heartbeat received"
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    val exponent = minOf((ln(value.toDouble()) / ln(1024.0)).toInt(), units.size)
    val amount = value / 1024.0.pow(exponent.toDouble())
    return String.format("%.1f %s", amount, units[exponent - 1])
}

private fun formatRate(value: Long): String = "${formatBytes(value)}/s"

private fun formatKbps(value: Int): String = when {
    value <= 0 -> "—"
    value >= 1_000_000 -> String.format("%.1f Gbps", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1f Mbps", value / 1_000.0)
    else -> "$value Kbps"
}
