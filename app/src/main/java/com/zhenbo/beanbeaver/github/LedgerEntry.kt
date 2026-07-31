package com.zhenbo.beanbeaver.github

import android.content.Context
import com.zhenbo.beanbeaver.export.LedgerFileOptions
import com.zhenbo.beanbeaver.receipt.ms
import com.zhenbo.beanbeaver.receipt.totalMs
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
         *
         * The `.json` sidecar is attached only when the user has the "Save details
         * file" option on ([LedgerFileOptions.includeDetailsJson]) — destinations
         * skip a null `jsonBytes`.
         */
        fun make(
            context: Context,
            result: ReceiptResult,
            imageBytes: ByteArray?,
            wallMs: Double?,
        ): LedgerEntry {
            val document = if (result.documentRelpath != null && imageBytes != null) imageBytes else null
            val json = if (LedgerFileOptions.includeDetailsJson(context)) {
                detailsJson(result, wallMs).toByteArray()
            } else {
                null
            }
            return LedgerEntry(
                beancount = result.beancount,
                documentBytes = document,
                documentRelpath = result.documentRelpath,
                jsonBytes = json,
                merchantSlug = merchantSlug(result.merchant),
                beanbeaverId = result.beanbeaverId,
            )
        }

        /**
         * The sidecar's contents as pretty text, regardless of whether the user
         * has "Save details file" on — the preview screen shows the raw parse so
         * it can be checked *before* deciding to export it.
         */
        fun detailsJson(result: ReceiptResult, wallMs: Double?): String =
            buildJson(result, wallMs).toString(2)

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
                        // The resolved beancount account, and the tag paths
                        // least-specific first (`["grocery", "grocery/dairy"]`).
                        // Paths rather than labels: the sidecar keeps the full
                        // classification even if the tag → account mapping or
                        // the authored wording later changes.
                        .put("account", item.account ?: JSONObject.NULL)
                        .put("tags", JSONArray(item.tags.map { it.path })),
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
