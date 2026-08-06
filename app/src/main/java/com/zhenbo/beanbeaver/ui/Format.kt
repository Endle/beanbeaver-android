package com.zhenbo.beanbeaver.ui

import uniffi.bb_receipt_ffi.ItemTag
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Display helpers ported from iOS `Theme.swift` so the two apps format the same
 * receipt data identically. Each falls back to the raw string unchanged when it
 * can't parse the input, so nothing is ever hidden or mangled.
 */

/** Normalized price for display, plus whether it was negative (a refund line). */
data class PriceDisplay(val text: String, val isNegative: Boolean)

/**
 * Prices/totals arrive loosely formatted from OCR (e.g. "17.1900", "-3.5000", or
 * an already-clean "$2.49") — normalize to a consistent "$X.XX". iOS `PriceFormat.display`.
 */
fun formatPrice(raw: String): PriceDisplay {
    val value = priceValue(raw) ?: return PriceDisplay(raw, isNegative = false)
    val sign = if (value < 0) "-" else ""
    val text = "$sign$" + "%.2f".format(abs(value))
    return PriceDisplay(text, isNegative = value < 0)
}

/**
 * The numeric value behind a loosely-formatted price string, or null if it
 * isn't parseable. iOS `PriceFormat.value` — shared by [formatPrice], the
 * spending arithmetic (`SpendSummary`) and `MoneyManagerExport`, so there is one
 * parse of this OCR output, not three.
 */
fun priceValue(raw: String): Double? =
    raw.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()

/**
 * A computed amount as "$X.XX" — the summing side of the app (spending totals,
 * category rows, the home card) rather than the raw-string side [formatPrice]
 * handles. Single currency: `$` is hardcoded app-wide today, same as
 * [formatPrice]; reconciling that with `LedgerFormatPrefs.currency` is
 * pre-existing and out of scope here. iOS `PriceFormat.currency`.
 */
fun priceCurrency(amount: Double): String {
    val sign = if (amount < 0) "-" else ""
    return "$sign$" + "%.2f".format(abs(amount))
}

/** ISO `YYYY-MM-DD` → the way a person writes a date ("Mar 1, 2026"). iOS `ReceiptDateFormat`. */
fun friendlyDate(raw: String?): String? {
    if (raw == null) return null
    val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val parsed = runCatching { iso.parse(raw) }.getOrNull() ?: return raw
    return SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed)
}

/**
 * How an item's classifier tags render: the core emits labelled tag nodes
 * broad→specific (e.g. `[{grocery, "Grocery"}, {grocery/meat, "Meat"}]`), so the
 * last is the most specific — we lead with it as an accent chip and keep the
 * rest as quiet context. Empty tags → [primary] is null (the row shows
 * "Uncategorized"). iOS `CategoryDisplay.tagDisplay`.
 *
 * `display` is authored in the core's tag vocabulary and used verbatim — the
 * pre-v0.7.0 code capitalized the raw path, which is why `energy_drink` reached
 * the card as "Energy_drink".
 */
data class TagDisplay(val primary: String?, val rest: List<String>)

fun tagDisplay(tags: List<ItemTag>): TagDisplay {
    val cleaned = tags.filter { it.display.isNotEmpty() }
    val last = cleaned.lastOrNull() ?: return TagDisplay(primary = null, rest = emptyList())
    return TagDisplay(
        primary = last.display,
        rest = cleaned.dropLast(1).map { it.display },
    )
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
