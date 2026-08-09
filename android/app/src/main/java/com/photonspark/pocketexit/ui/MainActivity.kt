package com.photonspark.pocketexit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.ln
import kotlin.math.pow

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

@Composable
private fun PocketExitScreen(preferences: AppPreferences) {
    val savedConfig by preferences.state.collectAsState()
    val runtime by RuntimeStore.state.collectAsState()
    var form by remember { mutableStateOf(savedConfig) }
    var formMessage by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(savedConfig) { form = savedConfig }

    Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Pocket Exit", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Private Android exit node. Uses normal Android networking APIs only; no root, ADB, VPN service, or device-wide route changes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusCard(runtime)
            NetworkCard("Wi-Fi", runtime.wifi)
            NetworkCard("Cellular", runtime.cellular)

            SectionTitle("Agent configuration")
            OutlinedTextField(
                value = form.serverUrl,
                onValueChange = { form = form.copy(serverUrl = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                supportingText = { Text("HTTPS endpoint exposed by the Nginx container") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.nodeId,
                onValueChange = { form = form.copy(nodeId = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Node ID") },
                supportingText = { Text("Must match a key in AGENT_TOKENS_JSON") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.deviceName,
                onValueChange = { form = form.copy(deviceName = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Display name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.agentToken,
                onValueChange = { form = form.copy(agentToken = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Agent token") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Encrypted at rest with Android Keystore") },
                singleLine = true,
            )

            PolicyPicker("Control tunnel", form.controlPolicy) {
                form = form.copy(controlPolicy = it)
            }
            PolicyPicker("Proxy exit", form.exitPolicy) {
                form = form.copy(exitPolicy = it)
            }

            ToggleRow(
                label = "Start after reboot",
                detail = "Runs only while the agent itself is enabled",
                checked = form.autoStart,
                onChecked = { form = form.copy(autoStart = it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val error = form.validationError()
                        if (error == null) {
                            preferences.save(form)
                            formMessage = "Configuration saved"
                        } else {
                            formMessage = error
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }

                if (runtime.running) {
                    Button(
                        onClick = {
                            val disabled = form.copy(enabled = false)
                            preferences.save(disabled)
                            form = disabled
                            ExitNodeService.stop(context)
                            formMessage = "Stopping agent"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            val enabled = form.copy(enabled = true)
                            val error = enabled.validationError()
                            if (error == null) {
                                preferences.save(enabled)
                                form = enabled
                                ExitNodeService.start(context, restart = false)
                                formMessage = "Starting agent"
                            } else {
                                formMessage = error
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                }
            }

            if (runtime.running) {
                OutlinedButton(
                    onClick = {
                        val enabled = form.copy(enabled = true)
                        val error = enabled.validationError()
                        if (error == null) {
                            preferences.save(enabled)
                            ExitNodeService.start(context, restart = true)
                            formMessage = "Agent restarting with saved settings"
                        } else {
                            formMessage = error
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and restart transport") }
            }

            if (formMessage.isNotBlank()) {
                Text(formMessage, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "The dashboard can change control and exit policies remotely. New TCP and UDP circuits use the selected Android Network; existing circuits close if their bound path disappears.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(runtime: AgentRuntime) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (runtime.registered) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(runtime.statusMessage, fontWeight = FontWeight.Bold)
                Text(if (runtime.running) "RUNNING" else "STOPPED", style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
            InfoRow("Control route", runtime.activeControlNetwork.label)
            InfoRow("Negotiated HTTP", runtime.negotiatedProtocol)
            InfoRow("Active circuits", runtime.activeCircuits.toString())
            InfoRow("Tunnel upload", formatBytes(runtime.bytesUp))
            InfoRow("Tunnel download", formatBytes(runtime.bytesDown))
            InfoRow(
                "Last heartbeat",
                if (runtime.lastHeartbeatEpochMs > 0) {
                    DateFormat.getTimeInstance().format(Date(runtime.lastHeartbeatEpochMs))
                } else "—",
            )
            if (runtime.lastError.isNotBlank()) {
                Text(runtime.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NetworkCard(name: String, network: NetworkSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        network.validated -> "INTERNET"
                        network.available -> "NO VALIDATION"
                        else -> "UNAVAILABLE"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        network.validated -> MaterialTheme.colorScheme.primary
                        network.available -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            InfoRow("Interface", network.interfaceName.ifBlank { "—" })
            InfoRow("Address", network.addresses.firstOrNull() ?: "—")
            InfoRow("DNS", network.dnsServers.firstOrNull() ?: "—")
            InfoRow("Estimated link", "${formatKbps(network.downKbps)} down / ${formatKbps(network.upKbps)} up")
            InfoRow("Metered", if (network.metered) "Yes" else "No")
        }
    }
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
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun PocketExitTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    val exponent = minOf((ln(value.toDouble()) / ln(1024.0)).toInt(), units.size)
    val amount = value / 1024.0.pow(exponent.toDouble())
    return String.format("%.1f %s", amount, units[exponent - 1])
}

private fun formatKbps(value: Int): String = when {
    value <= 0 -> "—"
    value >= 1_000_000 -> String.format("%.1f Gbps", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1f Mbps", value / 1_000.0)
    else -> "$value Kbps"
}
