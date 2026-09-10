package com.photonspark.pocketexit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.OnboardingLink
import com.photonspark.pocketexit.network.PairingClient

/**
 * Everything a `pocketexit://configure` link can be waiting on. Only [Idle]
 * writes nothing and shows nothing; every other value owns a sheet, so a link
 * can never take effect without one of them being on screen first.
 */
internal sealed interface PairingFlow {
    data object Idle : PairingFlow

    /** A version 2 link, parsed and shown, with no request made yet. */
    data class Confirm(val link: OnboardingLink.Pairing) : PairingFlow

    /** The claim is in flight. */
    data class Working(val link: OnboardingLink.Pairing) : PairingFlow

    /** The claim came back with one of the typed failures. */
    data class Failed(
        val link: OnboardingLink.Pairing,
        val failure: PairingClient.Result.Failed,
    ) : PairingFlow

    /** A version 1 link, which carries its own token and only needs confirming. */
    data class Import(val config: AgentConfig) : PairingFlow

    /** The link did not parse. The message is the parser's own wording. */
    data class Rejected(val message: String) : PairingFlow
}

@Composable
internal fun PairingSheet(
    flow: PairingFlow,
    deviceName: String,
    onConfirmPairing: (OnboardingLink.Pairing) -> Unit,
    onConfirmImport: (AgentConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    when (flow) {
        PairingFlow.Idle -> Unit
        is PairingFlow.Confirm -> ConfirmSheet(flow.link, deviceName, onConfirmPairing, onDismiss)
        is PairingFlow.Working -> WorkingSheet(flow.link)
        is PairingFlow.Failed -> FailedSheet(flow.link, flow.failure, onConfirmPairing, onDismiss)
        is PairingFlow.Import -> ImportSheet(flow.config, onConfirmImport, onDismiss)
        is PairingFlow.Rejected -> RejectedSheet(flow.message, onDismiss)
    }
}

@Composable
private fun ConfirmSheet(
    link: OnboardingLink.Pairing,
    deviceName: String,
    onConfirm: (OnboardingLink.Pairing) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheetDialog(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.pair_confirm_title))
        SheetBody(stringResource(R.string.pair_confirm_lead))
        ServerFacts(link)
        SheetBody(
            text = stringResource(R.string.pair_consent, deviceName, formatOrigin(link.serverUrl)),
            color = Warning,
        )
        SheetActions(
            confirmText = stringResource(R.string.pair_confirm_action),
            onConfirm = { onConfirm(link) },
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun WorkingSheet(link: OnboardingLink.Pairing) {
    // Not dismissible: the claim consumes a single-use code, so backing out
    // halfway would leave the person unsure whether the code was spent.
    BottomSheetDialog(onDismissRequest = { }, dismissible = false) {
        SheetTitle(stringResource(R.string.pair_working_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val description = stringResource(R.string.pair_working_description)
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp).semantics { contentDescription = description },
                color = Download,
                strokeWidth = 3.dp,
            )
            Text(
                text = stringResource(R.string.pair_working_detail, formatOrigin(link.serverUrl)),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}

@Composable
private fun FailedSheet(
    link: OnboardingLink.Pairing,
    failure: PairingClient.Result.Failed,
    onRetry: (OnboardingLink.Pairing) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheetDialog(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.pair_failed_title))
        SheetBody(text = failureMessage(failure), color = Danger)
        SheetBody(stringResource(R.string.pair_failed_hint))
        ServerFacts(link)
        SheetActions(
            confirmText = stringResource(R.string.pair_retry),
            onConfirm = { onRetry(link) },
            onCancel = onDismiss,
            cancelText = stringResource(R.string.action_close),
        )
    }
}

/**
 * The claim reports why it failed; the wording lives here, with every other
 * string a person reads. The server's own detail is appended when it sent one,
 * because "that code was not accepted" and "that code expired four minutes ago"
 * lead to different next moves.
 */
@Composable
private fun failureMessage(failure: PairingClient.Result.Failed): String {
    val message = when (failure.reason) {
        PairingClient.Failure.CODE_REJECTED -> stringResource(R.string.pair_error_code_rejected)
        PairingClient.Failure.RATE_LIMITED -> stringResource(R.string.pair_error_rate_limited)
        PairingClient.Failure.REQUEST_REJECTED ->
            stringResource(R.string.pair_error_request_rejected)
        PairingClient.Failure.UNEXPECTED_STATUS ->
            stringResource(R.string.pair_error_unexpected_status, failure.status)
        PairingClient.Failure.UNREACHABLE -> stringResource(R.string.pair_error_unreachable)
        PairingClient.Failure.MALFORMED_RESPONSE ->
            stringResource(R.string.pair_error_malformed_response)
    }
    return if (failure.detail.isBlank()) {
        message
    } else {
        stringResource(R.string.pair_error_detail, message, failure.detail)
    }
}

@Composable
private fun ImportSheet(
    config: AgentConfig,
    onConfirm: (AgentConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheetDialog(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.pair_import_title))
        SheetBody(stringResource(R.string.pair_import_lead))
        StackedField(
            label = stringResource(R.string.pair_label_server),
            value = formatOrigin(config.normalizedServerUrl),
        )
        StackedField(
            label = stringResource(R.string.pair_label_node),
            value = config.nodeId,
        )
        SheetBody(text = stringResource(R.string.pair_import_note), color = Warning)
        SheetActions(
            confirmText = stringResource(R.string.pair_import_action),
            onConfirm = { onConfirm(config) },
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun RejectedSheet(message: String, onDismiss: () -> Unit) {
    BottomSheetDialog(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.pair_link_rejected_title))
        SheetBody(text = message, color = Danger)
        SheetBody(stringResource(R.string.pair_link_rejected_hint))
        QuietButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The three facts the protocol says a person must see before confirming. */
@Composable
private fun ServerFacts(link: OnboardingLink.Pairing) {
    StackedField(
        label = stringResource(R.string.pair_label_server),
        value = formatOrigin(link.serverUrl),
    )
    StackedField(
        label = stringResource(R.string.pair_label_name),
        value = link.serverName.ifBlank { stringResource(R.string.pair_no_name) },
    )
    if (link.pin.isEmpty()) {
        StackedField(
            label = stringResource(R.string.pair_label_pin),
            value = stringResource(R.string.pair_pin_none),
            valueColor = Warning,
        )
    } else {
        StackedField(
            label = stringResource(R.string.pair_label_pin),
            value = shortPin(link.pin),
            valueColor = Signal,
        )
        Text(
            text = stringResource(R.string.pair_pin_full, groupedPin(link.pin)),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun SheetActions(
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelText: String = stringResource(R.string.action_cancel),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuietButton(
            text = cancelText,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            contentColor = Muted,
        )
        PrimaryButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
        )
    }
}
