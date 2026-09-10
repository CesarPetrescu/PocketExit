package com.photonspark.pocketexit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photonspark.pocketexit.R

/**
 * First run. There is nothing to configure yet, so the screen only explains
 * what is about to happen and points at the phone's own camera; the manual form
 * stays available for server-mode deployments but never leads.
 */
@Composable
internal fun PairingScreen(
    message: String,
    onManualSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        containerColor = CanvasBlack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.welcome_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = Download,
            )
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                text = stringResource(R.string.welcome_lead),
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
            )

            if (message.isNotBlank()) MessageBanner(message)

            SectionCard {
                CardTitle(stringResource(R.string.welcome_steps_title))
                PairingStep(
                    number = 1,
                    title = stringResource(R.string.welcome_step_1_title),
                    detail = stringResource(R.string.welcome_step_1_detail),
                )
                PairingStep(
                    number = 2,
                    title = stringResource(R.string.welcome_step_2_title),
                    detail = stringResource(R.string.welcome_step_2_detail),
                )
                PairingStep(
                    number = 3,
                    title = stringResource(R.string.welcome_step_3_title),
                    detail = stringResource(R.string.welcome_step_3_detail),
                )
            }

            SectionCard(container = PanelRaised) {
                Text(
                    text = stringResource(R.string.welcome_safety_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Signal,
                )
                Text(
                    text = stringResource(R.string.welcome_safety_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }

            Text(
                text = stringResource(R.string.welcome_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            QuietButton(
                text = stringResource(R.string.welcome_manual),
                onClick = onManualSetup,
                modifier = Modifier.fillMaxWidth(),
                contentColor = Muted,
            )
        }
    }
}

@Composable
private fun PairingStep(number: Int, title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(PanelRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.welcome_step_number, number),
                style = MaterialTheme.typography.labelLarge,
                color = Download,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}
