package com.photonspark.pocketexit.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

// Presentation-only helpers. Everything here is display formatting; nothing
// here decides behaviour.

internal fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    val exponent = minOf((ln(value.toDouble()) / ln(1024.0)).toInt(), units.size)
    val amount = value / 1024.0.pow(exponent.toDouble())
    return String.format(Locale.US, "%.1f %s", amount, units[exponent - 1])
}

internal fun formatRate(value: Long): String = "${formatBytes(value)}/s"

internal fun formatKbps(value: Int): String = when {
    value <= 0 -> "—"
    value >= 1_000_000 -> String.format(Locale.US, "%.1f Gbps", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1f Mbps", value / 1_000.0)
    else -> "$value Kbps"
}

internal fun formatClockTime(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(epochMs))

/** The origin without its scheme, which is what a person recognises at a glance. */
internal fun formatOrigin(serverUrl: String): String =
    serverUrl.trim().trimEnd('/').removePrefix("https://").ifBlank { serverUrl }

/**
 * A 43-character base64url pin is unreadable as one run of text. Grouping it in
 * fours makes it comparable against the fingerprint on the computer's screen.
 */
internal fun groupedPin(pin: String): String = pin.chunked(PIN_GROUP).joinToString(" ")

/** The leading groups only, for places where the whole pin would dominate. */
internal fun shortPin(pin: String): String {
    val head = pin.take(SHORT_PIN_CHARS)
    val tail = if (pin.length > SHORT_PIN_CHARS) "…" else ""
    return groupedPin(head) + tail
}

private const val PIN_GROUP = 4
private const val SHORT_PIN_CHARS = 16
