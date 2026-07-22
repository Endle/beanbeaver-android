package com.beanbeaver.app.github

import com.beanbeaver.app.receipt.ms
import com.beanbeaver.app.receipt.totalMs
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.Phase
import uniffi.bb_receipt_ffi.ReceiptResult

/**
 * One transaction to export, plus the optional receipt image and JSON sidecar
 * that travel with it. Kotlin twin of iOS `LedgerEntry`. `documentBytes` is null
 * when the scan produced no content hash or the captured JPEG is unavailable —
 * export then falls back to text-only.
 */
data class LedgerEntry(
    val beancount: String,
    val documentBytes: ByteArray?,
    val documentRelpath: String?,
    /** The pre-beancount scan data, serialized as a `.json` sidecar. */
    val jsonBytes: ByteArray?,
    /** Lowercase-dash merchant slug, matching `documentRelpath`'s convention. */
    val merchantSlug: String,
    /** `bb-<yyyymmdd|unknowndate>-<sha8>` — the identity token filenames derive from. */
    val beanbeaverId: String?,
) {
    companion object {
        /**
         * Build the entry the export destination receives from a finished scan.
         * [imageBytes] is the captured JPEG (in memory); [wallMs] is the observed
         * total scan time, folded into the sidecar's timings.
         */
        fun make(result: ReceiptResult, imageBytes: ByteArray?, wallMs: Double?): LedgerEntry {
            val document = if (result.documentRelpath != null && imageBytes != null) imageBytes else null
            return LedgerEntry(
                beancount = result.beancount,
                documentBytes = document,
                documentRelpath = result.documentRelpath,
                jsonBytes = buildJson(result, wallMs).toString(2).toByteArray(),
                merchantSlug = merchantSlug(result.merchant),
                beanbeaverId = result.beanbeaverId,
            )
        }

        /**
         * The `.json` sidecar: the structured parse before beancount formatting,
         * so the raw scan survives even if the beancount rendering rules change.
         * Mirrors iOS `ReceiptExportJSON`.
         */
        private fun buildJson(result: ReceiptResult, wallMs: Double?): JSONObject {
            val items = JSONArray()
            result.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("description", item.description)
                        .put("price", item.price)
                        .put("quantity", item.quantity)
                        .put("category", item.category ?: JSONObject.NULL)
                        .put("tags", JSONArray(item.tags)),
                )
            }
            val t = result.timings
            val timings = JSONObject()
                .put("prepMs", t.ms(Phase.PREP))
                .put("detectMs", t.ms(Phase.DETECT))
                .put("classifyMs", t.ms(Phase.CLASSIFY))
                .put("recognizeMs", t.ms(Phase.RECOGNIZE))
                .put("parseMs", t.ms(Phase.PARSE))
                .put("totalMs", t.totalMs)
            if (wallMs != null) timings.put("wallMs", wallMs)

            return JSONObject()
                .put("merchant", result.merchant)
                .put("date", result.date ?: JSONObject.NULL)
                .put("dateIsPlaceholder", result.dateIsPlaceholder)
                .put("total", result.total)
                .put("subtotal", result.subtotal ?: JSONObject.NULL)
                .put("tax", result.tax ?: JSONObject.NULL)
                .put("items", items)
                .put("warnings", JSONArray(result.warnings))
                .put("timings", timings)
        }

        /**
         * Lowercase, dash-collapsed slug (e.g. `COSTCO WHOLESALE #123` →
         * `costco-wholesale-123`) — mirrors `receipt-core`'s `merchant_slug` so
         * filenames built here agree with `documentRelpath`. Never empty.
         */
        private fun merchantSlug(merchant: String): String {
            val sb = StringBuilder()
            var previousDash = false
            for (ch in merchant.lowercase()) {
                val isAlphanumeric = ch.code < 128 && (ch.isLetter() || ch.isDigit())
                val normalized = if (isAlphanumeric) ch else '-'
                if (normalized == '-') {
                    if (previousDash) continue
                    previousDash = true
                } else {
                    previousDash = false
                }
                sb.append(normalized)
            }
            val slug = sb.toString().trim('-')
            return slug.ifEmpty { "unknown" }
        }
    }
}
