package com.zhenbo.beanbeaver.receipt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.util.UUID

/**
 * Every receipt ever scanned, kept indefinitely until the user removes it — the
 * substrate the spending screen and the Receipts screen are both views over.
 * Kotlin twin of iOS `SpendStore`.
 *
 * Owns the lifetime of each receipt's captured photo: deleting a record deletes
 * its photo, and clearing a photo leaves the record (and every spend figure it
 * contributes to) untouched. This is what lets `ReceiptCaptureStore.clearOld`
 * go away — nothing here ages out on its own.
 *
 * A process-wide object rather than a ViewModel, for the same reason as
 * [ItemRuleStore]: both scan paths record into it and several screens read it,
 * and none of them should be able to hold a stale copy.
 */
object SpendStore {

    /** Newest first. */
    private val _records = MutableStateFlow<List<SpendRecord>>(emptyList())
    val records: StateFlow<List<SpendRecord>> = _records.asStateFlow()

    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        _records.value = load(context)
    }

    // MARK: - Recording

    /**
     * Insert a freshly scanned receipt at the front. De-duplicated on
     * `result.beanbeaverId` when the core supplied one — the same identity GitHub
     * files under, so scanning the same photo twice doesn't double-count. A null
     * id (no image hash) records unconditionally.
     */
    @Synchronized
    fun record(context: Context, result: ReceiptResult, captureFilename: String?, wallMs: Double?) {
        ensureLoaded(context)
        val id = result.beanbeaverId
        if (id != null && _records.value.any { it.result.beanbeaverId == id }) return
        _records.value = listOf(
            SpendRecord(
                id = UUID.randomUUID().toString(),
                result = result,
                scannedAt = System.currentTimeMillis(),
                captureFilename = captureFilename,
                wallMs = wallMs,
            ),
        ) + _records.value
        save(context)
    }

    // MARK: - Mutation

    @Synchronized
    fun setExcluded(context: Context, id: String, excluded: Boolean) {
        ensureLoaded(context)
        _records.value = _records.value.map {
            if (it.id == id) it.copy(isExcluded = excluded) else it
        }
        save(context)
    }

    /**
     * Mark every record whose `beanbeaverId` is in [ids] as having reached
     * [target] (e.g. "GitHub"), stamping `exportedAt` the first time. Called from
     * the one hook each export path already has, so every call site benefits
     * without repeating itself.
     */
    @Synchronized
    fun markExported(context: Context, ids: List<String>, target: String) {
        if (ids.isEmpty()) return
        ensureLoaded(context)
        val idSet = ids.toSet()
        var changed = false
        _records.value = _records.value.map { record ->
            val beanbeaverId = record.result.beanbeaverId
            if (beanbeaverId == null || beanbeaverId !in idSet) return@map record
            changed = true
            record.copy(
                exportedAt = record.exportedAt ?: System.currentTimeMillis(),
                exportedTargets = if (target in record.exportedTargets) {
                    record.exportedTargets
                } else {
                    record.exportedTargets + target
                },
            )
        }
        if (changed) save(context)
    }

    /**
     * Same idea as [markExported], keyed by the results a Money Manager
     * presentation site already has on hand rather than ids a caller has to
     * extract first. Marked at presentation, not confirmed delivery — the share
     * sheet may be cancelled — which is why the row says "Shared", never "Filed".
     */
    fun markShared(
        context: Context,
        results: List<ReceiptResult>,
        target: String = "Money Manager",
    ) = markExported(context, results.mapNotNull { it.beanbeaverId }, target)

    /** Drop the row **and its photo**. The store owns photo lifetime now. */
    @Synchronized
    fun remove(context: Context, id: String) = remove(context, setOf(id))

    /**
     * Drop a chosen set of rows and their photos in one pass — the middle ground
     * between removing one and removing all, so tidying up a handful of receipts
     * isn't one swipe at a time. Saves once, not once per row.
     */
    @Synchronized
    fun remove(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        ensureLoaded(context)
        val before = _records.value.size
        _records.value.filter { it.id in ids }.forEach { deletePhoto(context, it) }
        _records.value = _records.value.filterNot { it.id in ids }
        if (_records.value.size != before) save(context)
    }

    /** Every row and photo, gone. */
    @Synchronized
    fun removeAll(context: Context) {
        ensureLoaded(context)
        _records.value.forEach { deletePhoto(context, it) }
        _records.value = emptyList()
        save(context)
    }

    /**
     * Drop rows whose capture matches [filenames] — used when a batch draft is
     * discarded (an explicit "I don't want this receipt") before it's ever
     * exported, so a discarded import doesn't quietly stay in someone's spend.
     * Doesn't touch the photo file itself: the caller ([ReceiptBatch]) owns that
     * deletion.
     */
    @Synchronized
    fun removeRecords(context: Context, filenames: Set<String>) {
        if (filenames.isEmpty()) return
        ensureLoaded(context)
        val before = _records.value.size
        _records.value = _records.value.filterNot { it.captureFilename in filenames }
        if (_records.value.size != before) save(context)
    }

    /** Delete one receipt's photo, keeping the row — the figures stay, the JPEG doesn't. */
    @Synchronized
    fun clearPhoto(context: Context, id: String) {
        ensureLoaded(context)
        _records.value = _records.value.map { record ->
            if (record.id != id || record.photoClearedAt != null) return@map record
            deletePhoto(context, record)
            record.copy(photoClearedAt = System.currentTimeMillis())
        }
        save(context)
    }

    /**
     * Delete every photo, keeping every row — the honest successor to the old
     * "Clear Old Receipts": same relief, no heuristic, and every spend figure
     * stays intact.
     */
    @Synchronized
    fun clearAllPhotos(context: Context) {
        ensureLoaded(context)
        _records.value = _records.value.map { record ->
            if (record.photoClearedAt != null) return@map record
            deletePhoto(context, record)
            record.copy(photoClearedAt = System.currentTimeMillis())
        }
        save(context)
    }

    // MARK: - Photo state

    /**
     * [SpendRecord.PhotoState.PRESENT] when the file is actually on disk,
     * `CLEARED` when the user cleared it, `UNAVAILABLE` when it's gone with no
     * clear stamp — a failed capture write, a storage-pressure purge, or a restore
     * that didn't bring the directory.
     *
     * Kept as three states rather than inferred from file-absence alone:
     * collapsing `CLEARED` and `UNAVAILABLE` would make the app look broken every
     * time the user tidied up, and `UNAVAILABLE` is worth surfacing since a
     * re-export of that row can attach no `document:` link.
     */
    fun photoState(context: Context, record: SpendRecord): SpendRecord.PhotoState = when {
        record.photoClearedAt != null -> SpendRecord.PhotoState.CLEARED
        record.captureFilename?.let { ReceiptCaptureStore.file(context, it).exists() } == true ->
            SpendRecord.PhotoState.PRESENT
        else -> SpendRecord.PhotoState.UNAVAILABLE
    }

    fun photoFile(context: Context, record: SpendRecord): File? =
        record.captureFilename
            ?.takeIf { photoState(context, record) == SpendRecord.PhotoState.PRESENT }
            ?.let { ReceiptCaptureStore.file(context, it) }

    /** How much disk the photos of kept receipts are using, for the Settings row. */
    fun totalPhotoBytes(context: Context): Long =
        _records.value.sumOf { photoFile(context, it)?.length() ?: 0L }

    // MARK: - Storage

    private fun file(context: Context): File =
        File(ReceiptCaptureStore.directory(context), "spend.json")

    private fun load(context: Context): List<SpendRecord> {
        val f = file(context)
        val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONObject(text).getJSONArray("records")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                // Unlike ReceiptBatch.load, a record whose photo is missing is kept
                // rather than dropped: there a photo-less draft is unusable (it
                // still has to be parsed); here the parse is already done and the
                // numbers are the asset. photoState reports UNAVAILABLE for it.
                SpendRecord(
                    id = o.getString("id"),
                    result = ReceiptResultJson.decode(o.getJSONObject("result")),
                    scannedAt = o.getLong("scannedAt"),
                    captureFilename = if (o.isNull("captureFilename")) null else o.getString("captureFilename"),
                    wallMs = if (o.isNull("wallMs")) null else o.getDouble("wallMs"),
                    isExcluded = o.optBoolean("isExcluded", false),
                    photoClearedAt = if (o.isNull("photoClearedAt")) null else o.getLong("photoClearedAt"),
                    exportedAt = if (o.isNull("exportedAt")) null else o.getLong("exportedAt"),
                    exportedTargets = o.optJSONArray("exportedTargets")?.let { targets ->
                        (0 until targets.length()).map { targets.getString(it) }
                    } ?: emptyList(),
                )
            }
        }.getOrElse {
            Log.e(TAG, "couldn't read spend.json", it)
            emptyList()
        }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        _records.value.forEach { record ->
            arr.put(
                JSONObject()
                    .put("id", record.id)
                    .put("result", ReceiptResultJson.encode(record.result))
                    .put("scannedAt", record.scannedAt)
                    .put("captureFilename", record.captureFilename ?: JSONObject.NULL)
                    .put("wallMs", record.wallMs ?: JSONObject.NULL)
                    .put("isExcluded", record.isExcluded)
                    .put("photoClearedAt", record.photoClearedAt ?: JSONObject.NULL)
                    .put("exportedAt", record.exportedAt ?: JSONObject.NULL)
                    .put("exportedTargets", JSONArray(record.exportedTargets)),
            )
        }
        runCatching { file(context).writeText(JSONObject().put("records", arr).toString()) }
            .onFailure { Log.e(TAG, "couldn't persist spend.json", it) }
    }

    private fun deletePhoto(context: Context, record: SpendRecord) {
        val filename = record.captureFilename ?: return
        runCatching { ReceiptCaptureStore.file(context, filename).delete() }
    }

    /**
     * Replace one record's parse with a corrected one (the Review & Fix editor).
     *
     * The whole [ReceiptResult], not a patch: an edit goes back through
     * `reformatReceipt`, so the beancount, the accounts, the tags and the
     * warnings are all re-derived together and there is no version of this record
     * where some of them are corrected and the rest are not.
     *
     * Every spending figure follows from [records], so this is also what makes a
     * corrected price reach the month — no separate invalidation, because
     * `SpendSummary` is computed from these rows on each read.
     */
    fun updateResult(context: Context, id: String, result: ReceiptResult) {
        val current = _records.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        _records.value = current.toMutableList().also {
            it[index] = it[index].copy(result = result)
        }
        save(context)
    }

    /**
     * The same update addressed by identity rather than row, for the scan result
     * screen — it holds a [ReceiptResult] straight from the pipeline and never
     * learns the [SpendRecord.id] that recording it minted.
     *
     * Matched on the receipt's *previous* `beanbeaverId`, since correcting the
     * date changes the id the edited copy will carry. A receipt with no id can't
     * be located this way and is left alone.
     */
    fun updateResult(context: Context, previous: ReceiptResult, result: ReceiptResult) {
        val id = previous.beanbeaverId ?: return
        val index = _records.value.indexOfFirst { it.result.beanbeaverId == id }
        if (index < 0) return
        updateResult(context, _records.value[index].id, result)
    }

    private const val TAG = "SpendStore"
}
