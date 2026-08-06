package com.zhenbo.beanbeaver.receipt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.util.UUID

/**
 * One scanned receipt's persisted record: its parsed data, the state of its
 * photo, and whether it's reached an export target yet. This — not the receipt
 * total — is what a month's spend is computed from (`SpendSummary`), and what
 * `ReceiptsScreen` lists. See [SpendStore] for the store that owns these.
 */
data class SpendRecord(
    val id: String,
    val result: ReceiptResult,
    val scannedAt: Long,
    /** Bare filename in [ReceiptCaptureStore.directory], never an absolute path —
     *  container paths go stale across updates. Null when the capture write itself
     *  failed. */
    val captureFilename: String?,
    val wallMs: Double?,
    /** Kept out of every budget total — returned, business, not mine. Budget-scoped
     *  only; the stored parse and what an export ships are untouched. */
    var isExcluded: Boolean = false,
    /** Set when the *user* clears the photo, so "you cleared this" can be said
     *  plainly and told apart from a file that went missing on its own. */
    var photoClearedAt: Long? = null,
    /** Set the first time this receipt reaches any export target, cleared never. */
    var exportedAt: Long? = null,
    /** Which targets it has reached, dedup'd — "GitHub" and/or "Money Manager".
     *  Plural because a receipt can legitimately go to both, and the row should
     *  say which. */
    var exportedTargets: List<String> = emptyList(),
) {
    /** Three states, not two, because they read differently to a user and only
     *  one of them is a problem — see [SpendStore.photoState]. */
    enum class PhotoState { PRESENT, CLEARED, UNAVAILABLE }

    val isExported: Boolean get() = exportedAt != null
}

/**
 * Every receipt ever scanned, kept indefinitely until the user removes it —
 * the substrate the spending screens and the Receipts list are both views over.
 * Owns the lifetime of each receipt's captured photo: deleting a record deletes
 * its photo, and clearing a photo leaves the record (and every spend figure it
 * contributes to) untouched. This is what let `ReceiptCaptureStore.clearOld` go
 * away — nothing here ages out on its own. Kotlin twin of iOS `SpendStore`.
 */
object SpendStore {
    private const val FILE_NAME = "spend.json"

    private val _records = MutableStateFlow<List<SpendRecord>>(emptyList())

    /** Every receipt, newest first. UI observes this. */
    val records: StateFlow<List<SpendRecord>> = _records.asStateFlow()

    private var loaded = false

    /** Load once from disk. Called by every public entry point and by the UI on
     *  screen entry, so a stale in-memory list can never be saved over real data. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        load(context)
        loaded = true
    }

    // MARK: Recording

    /**
     * Insert a freshly scanned receipt at the front. Dedup'd on
     * `result.beanbeaverId` when the core supplied one — the same identity
     * GitHub files under, so scanning the same photo twice doesn't double-count
     * in the spend figures. A null id (no image hash) records unconditionally.
     */
    fun record(context: Context, result: ReceiptResult, captureFilename: String?, wallMs: Double?) {
        ensureLoaded(context)
        if (result.beanbeaverId != null &&
            _records.value.any { it.result.beanbeaverId == result.beanbeaverId }
        ) {
            return
        }
        mutate(context) { list ->
            list.add(
                0,
                SpendRecord(
                    id = UUID.randomUUID().toString(),
                    result = result,
                    scannedAt = System.currentTimeMillis(),
                    captureFilename = captureFilename,
                    wallMs = wallMs,
                ),
            )
        }
    }

    // MARK: Mutation

    fun setExcluded(context: Context, id: String, excluded: Boolean) {
        ensureLoaded(context)
        mutate(context) { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = list[i].copy(isExcluded = excluded)
        }
    }

    /**
     * Mark every record whose `beanbeaverId` is in [ids] as having reached
     * [target] (e.g. "GitHub"), stamping `exportedAt` the first time. Called
     * from the one hook in the export path, so every ledger export call site
     * benefits without repeating itself.
     */
    fun markExported(context: Context, ids: List<String>, target: String) {
        ensureLoaded(context)
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        mutate(context) { list ->
            list.replaceAll { record ->
                val id = record.result.beanbeaverId
                if (id == null || id !in idSet) {
                    record
                } else {
                    record.copy(
                        exportedAt = record.exportedAt ?: System.currentTimeMillis(),
                        exportedTargets = if (target in record.exportedTargets) record.exportedTargets
                        else record.exportedTargets + target,
                    )
                }
            }
        }
    }

    /**
     * Same idea as [markExported], keyed by the results a Money Manager
     * presentation site already has on hand rather than ids a caller has to
     * extract first. Marked at presentation, not confirmed delivery — the share
     * sheet may be cancelled — which is why the row says "Shared", never "Filed".
     */
    fun markShared(context: Context, results: List<ReceiptResult>, target: String = "Money Manager") {
        markExported(context, results.mapNotNull { it.beanbeaverId }, target)
    }

    /** Drop the row **and its photo**. The store owns photo lifetime now, which
     *  is what lets the old sweep go away. */
    fun remove(context: Context, id: String) {
        ensureLoaded(context)
        val record = _records.value.firstOrNull { it.id == id } ?: return
        record.captureFilename?.let { ReceiptCaptureStore.delete(context, it) }
        mutate(context) { list -> list.removeAll { it.id == id } }
    }

    /** Drop a chosen set of rows and their photos in one pass — the middle ground
     *  between [remove] and [removeAll], so tidying up a handful of receipts isn't
     *  one delete at a time. Saves once, not once per row. */
    fun remove(context: Context, ids: Set<String>) {
        ensureLoaded(context)
        if (ids.isEmpty()) return
        _records.value.forEach { record ->
            if (ids.contains(record.id)) record.captureFilename?.let {
                ReceiptCaptureStore.delete(context, it)
            }
        }
        mutate(context) { list -> list.removeAll { ids.contains(it.id) } }
    }

    /** Every row and photo, gone. */
    fun removeAll(context: Context) {
        ensureLoaded(context)
        _records.value.forEach { record ->
            record.captureFilename?.let { ReceiptCaptureStore.delete(context, it) }
        }
        mutate(context) { it.clear() }
    }

    /**
     * Drop rows whose capture matches [captureFilenames] — used when a batch
     * draft is discarded (an explicit "I don't want this receipt") before it's
     * ever exported, so a discarded import doesn't quietly stay in someone's
     * spend figures. Doesn't touch the photo file itself: the caller
     * (`ReceiptBatch`) owns that deletion.
     */
    fun removeRecords(context: Context, captureFilenames: Set<String>) {
        ensureLoaded(context)
        if (captureFilenames.isEmpty()) return
        mutate(context) { list ->
            list.removeAll { record -> record.captureFilename in captureFilenames }
        }
    }

    /** Delete one receipt's photo, keeping the row — the figures stay, the JPEG
     *  doesn't. */
    fun clearPhoto(context: Context, id: String) {
        ensureLoaded(context)
        val i = _records.value.indexOfFirst { it.id == id }
        if (i < 0) return
        val record = _records.value[i]
        record.captureFilename?.let { ReceiptCaptureStore.delete(context, it) }
        mutate(context) { list ->
            list[i] = list[i].copy(photoClearedAt = System.currentTimeMillis())
        }
    }

    /**
     * Delete every photo, keeping every row — the honest successor to the old
     * `Clear Old Receipts`: same relief, no heuristic, and every spend figure
     * stays intact.
     */
    fun clearAllPhotos(context: Context) {
        ensureLoaded(context)
        mutate(context) { list ->
            list.indices.forEach { i ->
                val record = list[i]
                if (record.photoClearedAt == null) {
                    record.captureFilename?.let { ReceiptCaptureStore.delete(context, it) }
                    list[i] = record.copy(photoClearedAt = System.currentTimeMillis())
                }
            }
        }
    }

    // MARK: Photo state

    /** `.PRESENT` when the file is actually on disk, `.CLEARED` when the user
     *  cleared it, `.UNAVAILABLE` when it's gone with no clear stamp — a failed
     *  capture write or storage-pressure purge. */
    fun photoState(context: Context, record: SpendRecord): SpendRecord.PhotoState {
        ensureLoaded(context)
        if (record.photoClearedAt != null) return SpendRecord.PhotoState.CLEARED
        val filename = record.captureFilename ?: return SpendRecord.PhotoState.UNAVAILABLE
        return if (ReceiptCaptureStore.file(context, filename).exists()) {
            SpendRecord.PhotoState.PRESENT
        } else {
            SpendRecord.PhotoState.UNAVAILABLE
        }
    }

    /** The receipt photo's bytes, or null when it isn't on disk. */
    fun photoBytes(context: Context, record: SpendRecord): ByteArray? {
        if (photoState(context, record) != SpendRecord.PhotoState.PRESENT) return null
        return runCatching { ReceiptCaptureStore.file(context, record.captureFilename!!).readBytes() }.getOrNull()
    }

    /** Records that haven't reached any export target yet — the fast path for
     *  "back up everything I haven't yet". */
    fun unexportedRecords(context: Context): List<SpendRecord> {
        ensureLoaded(context)
        return _records.value.filter { !it.isExported }
    }

    fun totalPhotoBytes(context: Context): Long {
        ensureLoaded(context)
        return _records.value.sumOf { record ->
            if (photoState(context, record) == SpendRecord.PhotoState.PRESENT) {
                runCatching { ReceiptCaptureStore.file(context, record.captureFilename!!).length() }.getOrDefault(0L)
            } else 0L
        }
    }

    // MARK: Storage

    private fun file(context: Context): File =
        File(ReceiptCaptureStore.directory(context), FILE_NAME)

    private fun load(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("records")
            _records.value = (0 until arr.length()).mapNotNull { i -> decode(arr.getJSONObject(i)) }
        }
    }

    private fun encode(record: SpendRecord): JSONObject =
        JSONObject()
            .put("id", record.id)
            .put("result", ReceiptResultJson.encode(record.result))
            .put("scannedAt", record.scannedAt)
            .put("captureFilename", record.captureFilename ?: JSONObject.NULL)
            .put("wallMs", record.wallMs ?: JSONObject.NULL)
            .put("isExcluded", record.isExcluded)
            .put("photoClearedAt", record.photoClearedAt ?: JSONObject.NULL)
            .put("exportedAt", record.exportedAt ?: JSONObject.NULL)
            .put("exportedTargets", JSONArray(record.exportedTargets))

    private fun decode(o: JSONObject): SpendRecord? = runCatching {
        SpendRecord(
            id = o.getString("id"),
            result = ReceiptResultJson.decode(o.getJSONObject("result")),
            scannedAt = o.optLong("scannedAt", System.currentTimeMillis()),
            captureFilename = if (o.isNull("captureFilename")) null else o.getString("captureFilename"),
            wallMs = if (o.isNull("wallMs")) null else o.getDouble("wallMs"),
            isExcluded = o.optBoolean("isExcluded", false),
            photoClearedAt = if (o.isNull("photoClearedAt")) null else o.getLong("photoClearedAt"),
            exportedAt = if (o.isNull("exportedAt")) null else o.getLong("exportedAt"),
            exportedTargets = runCatching {
                val arr = o.getJSONArray("exportedTargets")
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList()),
        )
    }.getOrNull()

    /** Serialize every mutation through here so callers and the UI don't race. */
    @Synchronized
    private fun mutate(context: Context, block: (MutableList<SpendRecord>) -> Unit) {
        val next = _records.value.toMutableList()
        block(next)
        _records.value = next
        save(context, next)
    }

    private fun save(context: Context, records: List<SpendRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(encode(it)) }
        runCatching { file(context).writeText(JSONObject().put("records", arr).toString(2)) }
    }
}
