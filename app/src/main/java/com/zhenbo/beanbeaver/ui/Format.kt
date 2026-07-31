package com.zhenbo.beanbeaver.ui

import uniffi.bb_receipt_ffi.ItemTag
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
    val value = priceValue(raw) ?: return PriceDisplay(raw, isNegative = false)
    val sign = if (value < 0) "-" else ""
    val text = "$sign$" + "%.2f".format(kotlin.math.abs(value))
    return PriceDisplay(text, isNegative = value < 0)
}

/**
 * The numeric value behind an OCR'd price string, or null when it can't be read.
 * iOS `PriceFormat.value`.
 *
 * The one place a price becomes a number, so display and arithmetic can't
 * disagree about what "17.1900" is worth. A null is meaningful rather than
 * zero — [com.zhenbo.beanbeaver.receipt.SpendSummary] counts unreadable prices
 * instead of silently treating them as free.
 */
fun priceValue(raw: String?): Double? =
    raw?.filter { it.isDigit() || it == '.' || it == '-' }?.toDoubleOrNull()

/** A computed amount as the app writes money ("$8.42", "-$3.50"). iOS `PriceFormat.currency`. */
fun formatCurrency(value: Double): String {
    val sign = if (value < 0) "-" else ""
    return "$sign$" + "%.2f".format(kotlin.math.abs(value))
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
 * (e.g. `grocery`, `grocery/meat`, `grocery/meat/chicken`), so the last is the
 * most specific — we lead with it as an accent chip and keep the rest as quiet
 * context. Empty tags → [primary] is null (the row shows "Uncategorized").
 * iOS `CategoryDisplay.tagDisplay`.
 */
data class TagDisplay(val primary: String?, val rest: List<String>)

fun tagDisplay(tags: List<ItemTag>): TagDisplay {
    val cleaned = tags.filter { it.display.isNotEmpty() }
    val last = cleaned.lastOrNull() ?: return TagDisplay(primary = null, rest = emptyList())
    // `display` is authored in the core's tag vocabulary (v0.7.0), so it is used
    // verbatim. This used to capitalize the raw tag, which put `energy_drink` on
    // the card as "Energy_drink".
    return TagDisplay(primary = last.display, rest = cleaned.dropLast(1).map { it.display })
}

/**
 * A byte count the way a storage row reads it ("4.2 MB"). Decimal units, matching
 * what `ByteCountFormatter`'s `.file` style shows on iOS and what Android's own
 * storage screens use — so "12 MB here" and "12 MB in Settings" agree.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit])
    else "%.1f %s".format(value, units[unit])
}

/** "COSTCO WHOLESALE" → "Costco Wholesale" — iOS renders merchant/item names capitalized. */
fun titleCase(text: String): String =
    text.split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }
