package com.photonspark.pocketexit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The palette the app has always used, lifted out of the activity so every
// screen draws from one set of shades instead of inventing its own.
internal val Ink = Color(0xFFF8FAFC)
internal val Muted = Color(0xFF94A3B8)
internal val CanvasBlack = Color(0xFF020617)
internal val Panel = Color(0xFF0F172A)
internal val PanelRaised = Color(0xFF172033)
internal val Signal = Color(0xFF22C55E)
internal val Download = Color(0xFF38BDF8)
internal val Upload = Color(0xFFA78BFA)
internal val Warning = Color(0xFFFBBF24)
internal val Danger = Color(0xFFFB7185)

@Composable
internal fun PocketExitTheme(content: @Composable () -> Unit) {
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
            outline = Muted,
            error = Danger,
            onError = CanvasBlack,
        ),
        content = content,
    )
}
