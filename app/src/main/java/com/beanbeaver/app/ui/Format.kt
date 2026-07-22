package com.beanbeaver.app.ui

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Display helpers ported from iOS `Theme.swift` so the two apps format the same
 * receipt data identically. Each falls back to the raw string unchanged when it
 * can't parse the input, so nothing is ever hidden or mangled.
 */

/** Normalized price for display, plus whether it was negative (a refund line). */
data class PriceDisplay(val text: String, val isNegative: Boolean)

/**
 * Prices/totals arrive loosely formatted from OCR (e.g. "17.1900", "-3.5000", or
 * an already-clean "$2.49") — normalize to a consistent "$X.XX". iOS `PriceFormat`.
 */
fun formatPrice(raw: String): PriceDisplay {
    val filtered = raw.filter { it.isDigit() || it == '.' || it == '-' }
    val value = filtered.toDoubleOrNull() ?: return PriceDisplay(raw, isNegative = false)
    val sign = if (value < 0) "-" else ""
    val text = "$sign$" + "%.2f".format(kotlin.math.abs(value))
    return PriceDisplay(text, isNegative = value < 0)
}

/** ISO `YYYY-MM-DD` → the way a person writes a date ("Mar 1, 2026"). iOS `ReceiptDateFormat`. */
fun friendlyDate(raw: String?): String? {
    if (raw == null) return null
    val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val parsed = runCatching { iso.parse(raw) }.getOrNull() ?: return raw
    return SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed)
}

/**
 * How an item's classifier tags render: the classifier emits tags broad→specific
 * (e.g. `["grocery", "meat", "chicken"]`), so the last is the most specific — we
 * lead with it as an accent chip and keep the rest as quiet context. Empty tags →
 * [primary] is null (the row shows "Uncategorized"). iOS `CategoryDisplay.tagDisplay`.
 */
data class TagDisplay(val primary: String?, val rest: List<String>)

fun tagDisplay(tags: List<String>): TagDisplay {
    val cleaned = tags.filter { it.isNotEmpty() }
    val last = cleaned.lastOrNull() ?: return TagDisplay(primary = null, rest = emptyList())
    return TagDisplay(
        primary = last.replaceFirstChar { it.uppercase() },
        rest = cleaned.dropLast(1).map { s -> s.replaceFirstChar { it.uppercase() } },
    )
}

/** "COSTCO WHOLESALE" → "Costco Wholesale" — iOS renders merchant/item names capitalized. */
fun titleCase(text: String): String =
    text.split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }
