package com.beanbeaver.app.receipt

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beanbeaver.app.debug.DebugInfoStore
import com.beanbeaver.app.github.LedgerEntry
import com.beanbeaver.bbreceiptkit.ReceiptScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.MerchantMatchStatus
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Where a draft is in its life: queued → scanning → parsed/failed (or interrupted). */
sealed interface DraftState {
    data object Queued : DraftState
    data object Scanning : DraftState

    /** Persisted as scanning but seen at load — the app was killed mid-scan. Re-parsed like [Queued]. */
    data object Interrupted : DraftState
    data class Parsed(val result: ReceiptResult, val wallMs: Double?) : DraftState
    data class Failed(val message: String) : DraftState

    /**
     * Whether this draft still needs OCR. [Failed] is deliberately excluded: a scan
     * is deterministic in the bytes + models + settings, so re-running a failure
     * just arrives at the same message. Failures get a per-row Retry instead.
     */
    val needsParsing: Boolean
        get() = this is Queued || this is Interrupted
}

/**
 * One imported photo on its way to becoming a ledger transaction. The photo is the
 * durable thing and the parse is a cache of it — stored so reopening the page
 * doesn't re-run OCR over the whole pile, but always re-derivable from the JPEG.
 */
data class ReceiptDraft(
    val id: String,
    /** Bare filename in [ReceiptCaptureStore.directory]; a stored absolute path goes stale across reinstalls. */
    val captureFilename: String,
    /** SHA-256 of the JPEG — rejects a photo already in this batch, cheaper than a scan. */
    val contentHash: String,
    val state: DraftState,
    val addedAt: Long,
)

/** Whether the parsed result is worth a second look before it lands in a ledger. iOS `needsAttention`. */
val ReceiptResult.needsAttention: Boolean
    get() = warnings.isNotEmpty() ||
        merchantMatch.status == MerchantMatchStatus.SUGGESTED ||
        items.any { it.tags.isEmpty() }

/**
 * The photo-library import: a queue of receipts to parse, review, and export in one
 * go. Survives relaunch (see [batchFile]). Kotlin twin of iOS `ReceiptBatch`.
 *
 * Separate from [ReceiptPipeline], which drives the single camera scan — they share
 * the OCR session (via [OcrSessionProvider]) and [ReceiptCaptureStore], nothing
 * else. Photos are deleted only when a draft is removed; a parsed draft leaving the
 * batch on export leaves its photo behind for a future cleanup workflow.
 */
class ReceiptBatch(app: Application) : AndroidViewModel(app) {
    private val _drafts = MutableStateFlow<List<ReceiptDraft>>(emptyList())
    val drafts: StateFlow<List<ReceiptDraft>> = _drafts.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    /** Default credit-card account for the placeholder posting, mirroring [ReceiptPipeline]. */
    var creditCardAccount: String = "Liabilities:CreditCard"

    private var parseJob: Job? = null

    init {
        load()
    }

    // MARK: - Adding

    enum class AddOutcome { ADDED, DUPLICATE, FAILED }

    /**
     * Take one photo into the batch. Called per photo rather than per selection so
     * only one image is ever held in memory, however many the user picked. Rejects
     * a duplicate by content hash, so overlapping selections cost nothing.
     */
    fun add(imageData: ByteArray): AddOutcome {
        val hash = contentHash(imageData)
        if (_drafts.value.any { it.contentHash == hash }) return AddOutcome.DUPLICATE
        val file = ReceiptCaptureStore.newCaptureFile(getApplication())
        return try {
            file.writeBytes(imageData)
            mutate { it.add(ReceiptDraft(UUID.randomUUID().toString(), file.name, hash, DraftState.Queued, System.currentTimeMillis())) }
            AddOutcome.ADDED
        } catch (t: Throwable) {
            Log.e(TAG, "add failed", t)
            AddOutcome.FAILED
        }
    }

    // MARK: - Removing

    /** Drop a draft, photo and all — nothing else refers to the photo, and it's the user's storage. */
    fun remove(id: String) {
        val draft = _drafts.value.firstOrNull { it.id == id } ?: return
        deleteCapture(draft)
        mutate { list -> list.removeAll { it.id == id } }
    }

    /** Throw the whole batch away. Cancels any scan in flight — no point finishing one for a draft that's going. */
    fun discardAll() {
        stopParsing()
        _drafts.value.forEach(::deleteCapture)
        mutate { it.clear() }
    }

    /**
     * Drop everything that parsed — what a successful export drains. Photos stay for
     * a future cleanup workflow; leaving the batch is what makes them collectable.
     */
    fun removeParsed() {
        mutate { list -> list.removeAll { it.state is DraftState.Parsed } }
    }

    fun retry(id: String) {
        setState(id, DraftState.Queued)
        startParsing()
    }

    // MARK: - Parsing

    /**
     * Parse whatever is queued or interrupted, one at a time. Idempotent, so the
     * page can just call it on appear. Serial on purpose — OCR already saturates the
     * CPU, a second session would load the models twice, and one [OcrSession] can't
     * be driven by two scans at once.
     */
    fun startParsing() {
        if (parseJob?.isActive == true) return
        if (_drafts.value.none { it.state.needsParsing }) return
        _isParsing.value = true
        parseJob = viewModelScope.launch { parseLoop() }
    }

    /**
     * Stop after the scan in flight. That scan's result is kept — it's already paid
     * for — and anything not yet started goes back to interrupted. [_isParsing] stays
     * true until the loop's own `finally` clears it, which is honest: it is still
     * finishing the current scan.
     */
    fun stopParsing() {
        parseJob?.cancel()
        mutate { list ->
            list.replaceAll { if (it.state is DraftState.Scanning) it.copy(state = DraftState.Interrupted) else it }
        }
    }

    private suspend fun parseLoop() {
        val app = getApplication<Application>()
        try {
            while (true) {
                coroutineContext.ensureActive()
                val draft = _drafts.value.firstOrNull { it.state.needsParsing } ?: break
                setState(draft.id, DraftState.Scanning)
                val file = ReceiptCaptureStore.file(app, draft.captureFilename)
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null) {
                    setState(draft.id, DraftState.Failed("This receipt's photo is no longer on this device."))
                    continue
                }
                val account = creditCardAccount
                val currency = LedgerFormatPrefs.currency(app)
                val taxAccount = LedgerFormatPrefs.taxAccount(app)
                val started = System.nanoTime()
                try {
                    val result = withContext(Dispatchers.Default) {
                        val session = OcrSessionProvider.loaded(app)
                        ReceiptScanner.scan(session, bytes, account, currency, taxAccount)
                    }
                    val wallMs = (System.nanoTime() - started) / 1_000_000.0
                    setState(draft.id, DraftState.Parsed(result, wallMs))
                    DebugInfoStore.recordSuccess(app, result, wallMs)
                } catch (t: Throwable) {
                    Log.e(TAG, "batch scan failed", t)
                    setState(draft.id, DraftState.Failed(t.message ?: t.toString()))
                    DebugInfoStore.recordFailure(app, t)
                }
            }
        } finally {
            _isParsing.value = false
        }
    }

    // MARK: - Export

    /**
     * Every parsed receipt as a ledger entry, oldest first. The photo is read back
     * off disk here so its `document:` link resolves on the far side.
     */
    fun exportableEntries(): List<LedgerEntry> {
        val app = getApplication<Application>()
        return _drafts.value.mapNotNull { draft ->
            val parsed = draft.state as? DraftState.Parsed ?: return@mapNotNull null
            val bytes = runCatching { ReceiptCaptureStore.file(app, draft.captureFilename).readBytes() }.getOrNull()
            LedgerEntry.make(parsed.result, bytes, parsed.wallMs)
        }
    }

    // MARK: - State plumbing

    private fun setState(id: String, state: DraftState) {
        mutate { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = list[i].copy(state = state)
        }
    }

    /** Serialize every mutation through here so the parse loop and the picker loader don't race. */
    @Synchronized
    private fun mutate(block: (MutableList<ReceiptDraft>) -> Unit) {
        val next = _drafts.value.toMutableList()
        block(next)
        _drafts.value = next
        save(next)
    }

    private fun deleteCapture(draft: ReceiptDraft) {
        runCatching { ReceiptCaptureStore.file(getApplication(), draft.captureFilename).delete() }
    }

    private fun contentHash(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    // MARK: - Persistence

    private fun batchFile(): File = File(ReceiptCaptureStore.directory(getApplication()), "batch.json")

    private fun save(drafts: List<ReceiptDraft>) {
        val file = batchFile()
        if (drafts.isEmpty()) {
            runCatching { file.delete() }
            return
        }
        val arr = JSONArray()
        drafts.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("captureFilename", d.captureFilename)
                    .put("contentHash", d.contentHash)
                    .put("addedAt", d.addedAt)
                    .put("state", encodeState(d.state)),
            )
        }
        runCatching { file.writeText(JSONObject().put("drafts", arr).toString()) }
    }

    private fun encodeState(state: DraftState): JSONObject = when (state) {
        // In-flight states persist as their re-parseable form: a killed app can't
        // leave a scan half-done, and a queued draft just picks up on next launch.
        is DraftState.Queued, is DraftState.Scanning, is DraftState.Interrupted ->
            JSONObject().put("kind", "queued")
        is DraftState.Failed -> JSONObject().put("kind", "failed").put("message", state.message)
        is DraftState.Parsed -> JSONObject()
            .put("kind", "parsed")
            .put("wallMs", state.wallMs ?: JSONObject.NULL)
            .put("result", ReceiptResultJson.encode(state.result))
    }

    private fun load() {
        val file = batchFile()
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull() ?: return
        val app = getApplication<Application>()
        val restored = runCatching {
            val arr = JSONObject(text).getJSONArray("drafts")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val filename = o.getString("captureFilename")
                // The photo is the source of truth; a draft without one is unusable
                // (only reachable if the captures dir was cleared out from under us).
                if (!ReceiptCaptureStore.file(app, filename).exists()) return@mapNotNull null
                ReceiptDraft(
                    id = o.getString("id"),
                    captureFilename = filename,
                    contentHash = o.getString("contentHash"),
                    state = decodeState(o.getJSONObject("state")),
                    addedAt = o.getLong("addedAt"),
                )
            }
        }.getOrNull() ?: return
        _drafts.value = restored
    }

    private fun decodeState(o: JSONObject): DraftState = when (o.getString("kind")) {
        "parsed" -> DraftState.Parsed(
            result = ReceiptResultJson.decode(o.getJSONObject("result")),
            wallMs = if (o.isNull("wallMs")) null else o.getDouble("wallMs"),
        )
        "failed" -> DraftState.Failed(o.optString("message", "Couldn't read this one."))
        else -> DraftState.Queued
    }

    companion object {
        private const val TAG = "ReceiptBatch"
    }
}
