package com.beanbeaver.app.receipt

import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.FieldConfidences
import uniffi.bb_receipt_ffi.MerchantMatch
import uniffi.bb_receipt_ffi.MerchantMatchStatus
import uniffi.bb_receipt_ffi.Phase
import uniffi.bb_receipt_ffi.PhaseSpan
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.ScanTimings

/**
 * Hand-written JSON for the generated [ReceiptResult], so a parsed batch survives
 * relaunch without re-running OCR over the whole pile. The Kotlin twin of iOS's
 * `Codable` conformances in `ReceiptBatch.swift`.
 *
 * UniFFI emits plain data classes, so the scan types aren't serializable and we
 * can't reach into them to add it. Only the fields the batch UI and the ledger
 * export actually read are stored; the debug-only fields (`rawText`, `tenders`,
 * `confidence`, `detections`, `warningAfterItemIndices`, `imageFilename`) are
 * defaulted on decode. Keeping the on-disk shape narrow means an older batch file
 * still loads after the core grows new [ReceiptResult] fields.
 */
object ReceiptResultJson {

    fun encode(r: ReceiptResult): JSONObject {
        val items = JSONArray()
        r.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("description", item.description)
                    .put("price", item.price)
                    .put("quantity", item.quantity)
                    .put("category", item.category ?: JSONObject.NULL)
                    .put("tags", JSONArray(item.tags)),
            )
        }
        val spans = JSONArray()
        r.timings.spans.forEach { span ->
            spans.put(JSONObject().put("phase", span.phase.name).put("ms", span.ms))
        }
        val merchantMatch = JSONObject()
            .put("raw", r.merchantMatch.raw)
            .put("canonical", r.merchantMatch.canonical ?: JSONObject.NULL)
            .put("status", r.merchantMatch.status.name)
            .put("score", r.merchantMatch.score)

        return JSONObject()
            .put("merchant", r.merchant)
            .put("merchantMatch", merchantMatch)
            .put("date", r.date ?: JSONObject.NULL)
            .put("dateIsPlaceholder", r.dateIsPlaceholder)
            .put("total", r.total)
            .put("tax", r.tax ?: JSONObject.NULL)
            .put("subtotal", r.subtotal ?: JSONObject.NULL)
            .put("items", items)
            .put("warnings", JSONArray(r.warnings))
            .put("beancount", r.beancount)
            .put("beanbeaverId", r.beanbeaverId ?: JSONObject.NULL)
            .put("documentRelpath", r.documentRelpath ?: JSONObject.NULL)
            .put("timings", JSONObject().put("spans", spans))
    }

    fun decode(o: JSONObject): ReceiptResult {
        val mm = o.getJSONObject("merchantMatch")
        val merchantMatch = MerchantMatch(
            raw = mm.getString("raw"),
            canonical = mm.optNullableString("canonical"),
            status = runCatching { MerchantMatchStatus.valueOf(mm.getString("status")) }
                .getOrDefault(MerchantMatchStatus.UNKNOWN),
            score = mm.optDouble("score", 0.0),
        )

        val items = o.getJSONArray("items").objects().map { item ->
            ReceiptItem(
                description = item.getString("description"),
                price = item.getString("price"),
                quantity = item.getInt("quantity"),
                category = item.optNullableString("category"),
                tags = item.getJSONArray("tags").strings(),
            )
        }

        val spans = o.getJSONObject("timings").getJSONArray("spans").objects().mapNotNull { span ->
            val phase = runCatching { Phase.valueOf(span.getString("phase")) }.getOrNull()
                ?: return@mapNotNull null
            PhaseSpan(phase = phase, ms = span.getDouble("ms"))
        }

        return ReceiptResult(
            merchant = o.getString("merchant"),
            merchantMatch = merchantMatch,
            date = o.optNullableString("date"),
            dateIsPlaceholder = o.optBoolean("dateIsPlaceholder", false),
            total = o.getString("total"),
            tax = o.optNullableString("tax"),
            subtotal = o.optNullableString("subtotal"),
            items = items,
            warnings = o.getJSONArray("warnings").strings(),
            // Not persisted — defaulted so an old batch file still loads and the
            // card/export never depend on them (see the class KDoc).
            warningAfterItemIndices = emptyList(),
            rawText = "",
            imageFilename = "receipt.jpg",
            tenders = emptyList(),
            beancount = o.getString("beancount"),
            beanbeaverId = o.optNullableString("beanbeaverId"),
            documentRelpath = o.optNullableString("documentRelpath"),
            timings = ScanTimings(spans = spans),
            confidence = FieldConfidences(
                merchant = 0.0, date = 0.0, total = 0.0, itemsCategorized = 0.0,
                needsReview = false,
            ),
            detections = emptyList(),
        )
    }
}

// `optString` returns "" for JSON null, which would resurrect an empty string
// where the core expects absence — read null-or-missing as a real null instead.
// `isNull` is true for both an explicit null and an absent key, so after it the
// value is present and a plain `getString` is safe.
private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONArray.strings(): List<String> =
    (0 until length()).map { getString(it) }

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { getJSONObject(it) }
