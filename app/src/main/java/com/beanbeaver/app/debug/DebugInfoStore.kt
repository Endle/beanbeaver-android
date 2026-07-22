package com.beanbeaver.app.debug

import android.content.Context
import android.os.Build
import com.beanbeaver.app.BuildConfig
import com.beanbeaver.app.receipt.ms
import com.beanbeaver.app.receipt.totalMs
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.Phase
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs the opt-in "Store detailed debug info" setting (off by default), the
 * Kotlin twin of iOS `DebugInfoStore`. Enabling it keeps a full copy of each
 * scan's parsed contents — merchant, items, prices, raw OCR text, per-field
 * confidence, and the generated beancount — plus export/scan error detail, one
 * JSON file per event. That's more than BeanBeaver normally retains, so **every
 * entry point here is a no-op unless [isEnabled]** — nothing is written by default.
 */
object DebugInfoStore {
    const val ENABLED_KEY = "storeDetailedDebugInfo"
    private const val PREFS = "beanbeaver"
    private const val FILE_PREFIX = "debug_info_"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    private fun directory(context: Context): File =
        File(context.filesDir, "DebugInfo").apply { mkdirs() }

    /** Record a completed scan. No-op unless the setting is on. */
    fun recordSuccess(context: Context, result: ReceiptResult, wallMs: Double?) {
        if (!isEnabled(context)) return
        write(context, entry("success", receipt = receiptJson(result, wallMs), error = null))
    }

    /** Record a failed scan. No-op unless the setting is on. */
    fun recordFailure(context: Context, error: Throwable) {
        if (!isEnabled(context)) return
        write(context, entry("failed", receipt = null, error = error.toString()))
    }

    /** Record a ledger export failure. No-op unless the setting is on. */
    fun recordExportFailure(context: Context, label: String, message: String) {
        if (!isEnabled(context)) return
        write(context, entry("export_failed", receipt = null, error = "$label: $message"))
    }

    private fun entry(outcome: String, receipt: JSONObject?, error: String?): JSONObject {
        val environment = JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appBuild", BuildConfig.VERSION_CODE)
            .put("coreVersion", BuildConfig.CORE_VERSION)
            .put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        return JSONObject()
            .put("generatedAt", iso(Date()))
            .put("outcome", outcome)
            .put("environment", environment)
            .put("receipt", receipt ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }

    /**
     * Full snapshot of one parsed receipt — deliberately richer than the ledger
     * `.json` sidecar: the raw OCR dump, merchant resolution, per-field
     * confidence, tenders, and generated beancount, so a bad parse can be
     * understood off-device without re-running the scan. Mirrors iOS
     * `DebugReceiptJSON`.
     */
    private fun receiptJson(r: ReceiptResult, wallMs: Double?): JSONObject {
        val items = JSONArray()
        r.items.forEach {
            items.put(
                JSONObject()
                    .put("description", it.description)
                    .put("price", it.price)
                    .put("quantity", it.quantity)
                    .put("category", it.category ?: JSONObject.NULL)
                    .put("tags", JSONArray(it.tags)),
            )
        }
        val warnings = JSONArray()
        r.warnings.forEachIndexed { i, message ->
            val idx = r.warningAfterItemIndices.getOrElse(i) { -1 }
            warnings.put(
                JSONObject()
                    .put("message", message)
                    .put("afterItemIndex", if (idx >= 0) idx else JSONObject.NULL),
            )
        }
        val tenders = JSONArray()
        r.tenders.forEach {
            tenders.put(
                JSONObject()
                    .put("amount", it.amount)
                    .put("account", it.account ?: JSONObject.NULL)
                    .put("kind", it.kind)
                    .put("rawLabel", it.rawLabel),
            )
        }
        val merchantMatch = JSONObject()
            .put("raw", r.merchantMatch.raw)
            .put("canonical", r.merchantMatch.canonical ?: JSONObject.NULL)
            .put("status", r.merchantMatch.status.name)
            .put("score", r.merchantMatch.score)
        val confidence = JSONObject()
            .put("merchant", r.confidence.merchant)
            .put("date", r.confidence.date)
            .put("total", r.confidence.total)
            .put("itemsCategorized", r.confidence.itemsCategorized)
            .put("needsReview", r.confidence.needsReview)
        val t = r.timings
        val timings = JSONObject()
            .put("prepMs", t.ms(Phase.PREP))
            .put("detectMs", t.ms(Phase.DETECT))
            .put("classifyMs", t.ms(Phase.CLASSIFY))
            .put("recognizeMs", t.ms(Phase.RECOGNIZE))
            .put("parseMs", t.ms(Phase.PARSE))
            .put("totalMs", t.totalMs)
        if (wallMs != null) timings.put("wallMs", wallMs)

        return JSONObject()
            .put("merchant", r.merchant)
            .put("merchantMatch", merchantMatch)
            .put("date", r.date ?: JSONObject.NULL)
            .put("dateIsPlaceholder", r.dateIsPlaceholder)
            .put("total", r.total)
            .put("subtotal", r.subtotal ?: JSONObject.NULL)
            .put("tax", r.tax ?: JSONObject.NULL)
            .put("items", items)
            .put("warnings", warnings)
            .put("tenders", tenders)
            .put("confidence", confidence)
            .put("rawText", r.rawText)
            .put("imageFilename", r.imageFilename)
            .put("beancount", r.beancount)
            .put("beanbeaverId", r.beanbeaverId ?: JSONObject.NULL)
            .put("documentRelpath", r.documentRelpath ?: JSONObject.NULL)
            .put("timings", timings)
    }

    private fun write(context: Context, entry: JSONObject) {
        runCatching {
            val stamp = System.currentTimeMillis()
            File(directory(context), "$FILE_PREFIX$stamp.json").writeText(entry.toString(2))
        }
    }

    /** One stored debug file: newest first when listed. */
    data class StoredEntry(val name: String, val file: File, val modified: Long, val byteCount: Long) {
        val outcome: String
            get() = runCatching { JSONObject(file.readText()).optString("outcome") }.getOrDefault("")
    }

    fun entries(context: Context): List<StoredEntry> =
        directory(context).listFiles { f -> f.name.startsWith(FILE_PREFIX) }
            ?.map { StoredEntry(it.name, it, it.lastModified(), it.length()) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    fun clearAll(context: Context): Int {
        var count = 0
        entries(context).forEach { if (it.file.delete()) count++ }
        return count
    }

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(date)
}
