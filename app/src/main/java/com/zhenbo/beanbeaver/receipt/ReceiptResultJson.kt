package com.zhenbo.beanbeaver.receipt

import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.FieldConfidences
import uniffi.bb_receipt_ffi.ItemTag
import uniffi.bb_receipt_ffi.MerchantMatch
import uniffi.bb_receipt_ffi.MerchantMatchStatus
import uniffi.bb_receipt_ffi.Phase
import uniffi.bb_receipt_ffi.PhaseSpan
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.ReceiptWarning
import uniffi.bb_receipt_ffi.ReceiptWarningKind
import uniffi.bb_receipt_ffi.ScanTimings

/**
 * Hand-written JSON for the generated [ReceiptResult], so a parsed batch survives
 * relaunch without re-running OCR over the whole pile. The Kotlin twin of iOS's
 * `Codable` conformances in `ReceiptBatch.swift`.
 *
 * UniFFI emits plain data classes, so the scan types aren't serializable and we
 * can't reach into them to add it. Only the fields the batch UI and the ledger
 * export actually read are stored; the debug-only fields (`rawText`, `tenders`,
 * `confidence`, `detections`, `imageFilename`) are defaulted on decode. Keeping
 * the on-disk shape narrow means an older batch file still loads after the core
 * grows new [ReceiptResult] fields.
 */
object ReceiptResultJson {

    fun encode(r: ReceiptResult): JSONObject {
        val items = JSONArray()
        r.items.forEach { item ->
            val tags = JSONArray()
            item.tags.forEach { tag ->
                tags.put(JSONObject().put("path", tag.path).put("display", tag.display))
            }
            items.put(
                JSONObject()
                    .put("description", item.description)
                    .put("price", item.price)
                    .put("quantity", item.quantity)
                    .put("account", item.account ?: JSONObject.NULL)
                    .put("tags", tags),
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
            .put("warnings", encodeWarnings(r.warnings))
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
                // Pre-0.7.0 wrote a `category` holding a *classifier key*
                // (`grocery_dairy`), not a beancount account, so it is
                // deliberately NOT read into `account` — copying it across would
                // fabricate a wrong account. Such a draft decodes with a null
                // account and relies on its stored `beancount` text, which is
                // authoritative.
                account = item.optNullableString("account"),
                tags = item.decodeTags(),
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
            warnings = o.getJSONArray("warnings").decodeWarnings(),
            // Not persisted — defaulted so an old batch file still loads and the
            // card/export never depend on them (see the class KDoc).
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

/**
 * An item's tags, reading **both** on-disk shapes.
 *
 * A batch saved by an older build is still in the captures directory when the
 * app updates, so a pre-0.7.0 draft has to keep loading or the user loses an
 * in-progress import. Old tags were bare strings; only the last was ever shown,
 * so a capitalized fallback label renders a legacy draft exactly as it looked
 * before the upgrade.
 */
private fun JSONObject.decodeTags(): List<ItemTag> =
    (0 until getJSONArray("tags").length()).map { i ->
        when (val raw = getJSONArray("tags").get(i)) {
            is JSONObject -> ItemTag(
                path = raw.getString("path"),
                display = raw.optString("display", raw.getString("path")),
            )
            else -> raw.toString().let { path ->
                ItemTag(path = path, display = path.replaceFirstChar { it.uppercase() })
            }
        }
    }

private fun encodeWarnings(warnings: List<ReceiptWarning>): JSONArray {
    val arr = JSONArray()
    warnings.forEach { w ->
        arr.put(
            JSONObject()
                .put("kind", w.kind.name)
                .put("message", w.message)
                .put("afterItemIndex", w.afterItemIndex),
        )
    }
    return arr
}

/**
 * Findings, reading only the v0.8.0 object shape.
 *
 * A draft written before then holds bare strings, and a string's kind cannot be
 * recovered without pattern-matching the English — the exact thing kinds exist
 * to abolish, and a guess here would mis-rank a stored receipt forever. So a
 * legacy list is dropped rather than invented. What's lost is the "Heads up"
 * text on a draft already scanned and already reviewed; what's kept is
 * everything that matters later — the items, the totals, and the stored
 * beancount, whose own `; WARN:PARSER` comments still carry those messages
 * verbatim. (Contrast [decodeTags], where the old shape *is* recoverable.)
 */
private fun JSONArray.decodeWarnings(): List<ReceiptWarning> =
    (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        ReceiptWarning(
            // An unrecognized name means the file was written by a *newer*
            // build than this one — a downgrade, or a restored backup. Land on
            // the same fallback an unknown kind gets from `severity`: shown,
            // quietly.
            kind = runCatching { ReceiptWarningKind.valueOf(o.getString("kind")) }
                .getOrDefault(ReceiptWarningKind.POSSIBLE_MISSED_ITEM),
            message = o.optString("message"),
            afterItemIndex = o.optInt("afterItemIndex", -1),
        )
    }

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { getJSONObject(it) }
