package com.photonspark.pocketexit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photonspark.pocketexit.R

// Everything tappable is at least this tall, including the text buttons that
// Material would otherwise let shrink to 40dp.
internal val MinTouchTarget = 48.dp

@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = Panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
    )
}

/** Label and value side by side; both wrap rather than truncate under a large font scale. */
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color = Ink) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

/** Label above value, for values too long to sit opposite their label. */
@Composable
internal fun StackedField(label: String, value: String, valueColor: Color = Ink) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A distinct shape per connection state, so the status never depends on colour
 * alone. It is decorative: the label beside it carries the meaning, which is
 * why it publishes no semantics of its own.
 */
@Composable
internal fun StatusGlyph(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = statusColor(state)
    Canvas(modifier = modifier.size(18.dp)) {
        val extent = size.minDimension
        val stroke = extent * 0.17f
        when (state) {
            ConnectionState.ONLINE -> drawCircle(color, radius = extent / 2f)
            ConnectionState.CONNECTING -> drawCircle(
                color,
                radius = (extent - stroke) / 2f,
                style = Stroke(stroke),
            )
            ConnectionState.STOPPED -> drawRect(
                color,
                topLeft = Offset(extent * 0.12f, extent * 0.12f),
                size = Size(extent * 0.76f, extent * 0.76f),
            )
            ConnectionState.DEGRADED -> drawPath(
                Path().apply {
                    moveTo(extent / 2f, extent * 0.05f)
                    lineTo(extent * 0.97f, extent * 0.92f)
                    lineTo(extent * 0.03f, extent * 0.92f)
                    close()
                },
                color,
            )
            ConnectionState.NO_NETWORK -> {
                drawLine(
                    color,
                    Offset(extent * 0.14f, extent * 0.14f),
                    Offset(extent * 0.86f, extent * 0.86f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(extent * 0.86f, extent * 0.14f),
                    Offset(extent * 0.14f, extent * 0.86f),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
    }
}

internal fun statusColor(state: ConnectionState): Color = when (state) {
    ConnectionState.ONLINE -> Signal
    ConnectionState.CONNECTING -> Download
    ConnectionState.DEGRADED -> Warning
    ConnectionState.NO_NETWORK -> Warning
    ConnectionState.STOPPED -> Muted
}

@Composable
internal fun MessageBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Download.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Download,
        )
    }
}

@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Download,
    onContainer: Color = CanvasBlack,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = onContainer,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Download,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinTouchTarget),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * Title strip shared by the home and settings screens. The icon-only controls
 * carry content descriptions; the eyebrow is redundant with the title and is
 * left out of the reading order by keeping it plain text above it.
 */
@Composable
internal fun ScreenTopBar(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = "",
    onAction: (() -> Unit)? = null,
    actionDescription: String = "",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = backDescription,
                    tint = Ink,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = Download)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
        }
        if (onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = actionDescription,
                    tint = Ink,
                )
            }
        }
    }
}

/**
 * A bottom sheet built out of a plain dialog. It scrolls, so a long
 * confirmation still fits on a short screen or at a large font scale, and it
 * never covers the status bar.
 */
@Composable
internal fun BottomSheetDialog(
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        // Tap handling goes through pointerInput rather than clickable so the
        // scrim and the sheet body do not announce themselves as buttons; every
        // sheet carries an explicit cancel or close control instead.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CanvasBlack.copy(alpha = 0.74f))
                .pointerInput(dismissible) {
                    detectTapGestures { if (dismissible) onDismissRequest() }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 56.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                color = Panel,
                contentColor = Ink,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(38.dp)
                            .height(4.dp)
                            .background(Muted.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                    content()
                }
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Ink,
    )
}

@Composable
internal fun SheetBody(text: String, color: Color = Muted) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}
