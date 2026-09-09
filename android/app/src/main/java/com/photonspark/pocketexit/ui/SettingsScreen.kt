package com.photonspark.pocketexit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.Policy

/**
 * The form the old UI opened on. It is still here for server-mode deployments
 * and for changing routing after pairing, but it is now something the person
 * chooses to open rather than the first thing they meet.
 */
@Composable
internal fun SettingsScreen(
    form: AgentConfig,
    paired: Boolean,
    running: Boolean,
    message: String,
    onFormChanged: (AgentConfig) -> Unit,
    onSave: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        containerColor = CanvasBlack,
        bottomBar = {
            Surface(color = Panel, contentColor = Ink) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    PrimaryButton(
                        text = if (running) {
                            stringResource(R.string.settings_save_restart)
                        } else {
                            stringResource(R.string.settings_save)
                        },
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
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
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backDescription = stringResource(R.string.settings_back),
            )
            if (message.isNotBlank()) MessageBanner(message)

            if (!paired) {
                SectionCard(container = PanelRaised) {
                    Text(
                        text = stringResource(R.string.settings_manual_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        text = stringResource(R.string.settings_manual_lead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }

            SectionCard {
                CardTitle(stringResource(R.string.settings_section_server))
                OutlinedTextField(
                    value = form.serverUrl,
                    onValueChange = { onFormChanged(form.copy(serverUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_server_url)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.nodeId,
                    onValueChange = { onFormChanged(form.copy(nodeId = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_node_id)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.deviceName,
                    onValueChange = { onFormChanged(form.copy(deviceName = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_device_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.agentToken,
                    onValueChange = { onFormChanged(form.copy(agentToken = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_agent_token)) },
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(
                            onClick = { tokenVisible = !tokenVisible },
                            modifier = Modifier.heightIn(min = MinTouchTarget),
                        ) {
                            Text(
                                text = if (tokenVisible) {
                                    stringResource(R.string.settings_token_hide)
                                } else {
                                    stringResource(R.string.settings_token_show)
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                )
                // The pin arrives with the pairing code and is verified against
                // the computer's screen, so it is shown here but never typed.
                StackedField(
                    label = stringResource(R.string.settings_pin_label),
                    value = if (form.pin.isEmpty()) {
                        stringResource(R.string.settings_pin_platform)
                    } else {
                        groupedPin(form.pin)
                    },
                    valueColor = if (form.pin.isEmpty()) Muted else Signal,
                )
                Text(
                    text = stringResource(R.string.settings_secret_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            SectionCard {
                CardTitle(stringResource(R.string.settings_section_routing))
                PolicyPicker(
                    label = stringResource(R.string.settings_control_policy),
                    selected = form.controlPolicy,
                    onSelected = { onFormChanged(form.copy(controlPolicy = it)) },
                )
                PolicyPicker(
                    label = stringResource(R.string.settings_exit_policy),
                    selected = form.exitPolicy,
                    onSelected = { onFormChanged(form.copy(exitPolicy = it)) },
                )
            }

            SectionCard {
                CardTitle(stringResource(R.string.settings_section_startup))
                ToggleRow(
                    label = stringResource(R.string.settings_auto_start),
                    detail = stringResource(R.string.settings_auto_start_detail),
                    checked = form.autoStart,
                    onChecked = { onFormChanged(form.copy(autoStart = it)) },
                )
            }

            if (paired) {
                SectionCard {
                    CardTitle(stringResource(R.string.settings_section_pairing))
                    Text(
                        text = stringResource(R.string.settings_unpair_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    QuietButton(
                        text = stringResource(R.string.settings_unpair),
                        onClick = { confirmUnpair = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = Danger,
                    )
                }
            }
        }
    }

    if (confirmUnpair) {
        BottomSheetDialog(onDismissRequest = { confirmUnpair = false }) {
            SheetTitle(stringResource(R.string.settings_unpair_confirm_title))
            SheetBody(stringResource(R.string.settings_unpair_confirm_detail))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmUnpair = false },
                    modifier = Modifier.weight(1f),
                    contentColor = Muted,
                )
                PrimaryButton(
                    text = stringResource(R.string.settings_unpair),
                    onClick = {
                        confirmUnpair = false
                        onUnpair()
                    },
                    modifier = Modifier.weight(1f),
                    container = Danger,
                )
            }
        }
    }
}

@Composable
private fun PolicyPicker(label: String, selected: Policy, onSelected: (Policy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.settings_policy_menu, label, selected.label)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = description },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(selected.label, style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Policy.entries.forEach { policy ->
                    DropdownMenuItem(
                        text = { Text(policy.label, style = MaterialTheme.typography.bodyLarge) },
                        onClick = {
                            expanded = false
                            onSelected(policy)
                        },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
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
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Ink,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
