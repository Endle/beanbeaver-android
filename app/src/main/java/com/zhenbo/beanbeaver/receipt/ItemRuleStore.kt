package com.zhenbo.beanbeaver.receipt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.ParseOptions
import uniffi.bb_receipt_ffi.RuleBook
import java.io.File
import java.util.UUID

/**
 * A rule document the user brought in, stored verbatim.
 *
 * The TOML text is **copied**, not held as a `content://` permission grant: a
 * persisted URI permission survives reboots but not the source file being moved,
 * renamed or deleted, and rules have to keep working after that.
 */
data class ImportedRuleDocument(
    val id: String,
    /** Filename it was imported from, shown in the list. */
    val displayName: String,
    /** Epoch millis. */
    val importedAt: Long,
    /** The document itself — what gets handed to the core as an override layer. */
    val toml: String,
)

/**
 * Owns the user's imported rule documents and the [RuleBook] they produce.
 * Kotlin twin of iOS `ItemRuleStore`.
 *
 * Two responsibilities that are easy to conflate: this is the *only* thing that
 * persists user rules, and it is also what the scan path reads to build
 * [ParseOptions]. Keeping both here is what stops the browser from showing one
 * ruleset while scans use another.
 *
 * A process-wide `object` rather than a ViewModel, read at scan time (like
 * [LedgerFormatPrefs]) rather than snapshotted into the pipeline, so an import
 * takes effect on the very next scan without anything having to re-plumb it.
 * [ReceiptPipeline] and [ReceiptBatch] both scan, and neither should be able to
 * hold a stale corpus.
 */
object ItemRuleStore {

    private val _documents = MutableStateFlow<List<ImportedRuleDocument>>(emptyList())
    val documents: StateFlow<List<ImportedRuleDocument>> = _documents.asStateFlow()

    /**
     * The rule corpus currently in force: bundled defaults plus every imported
     * document, later ones winning. Rebuilt whenever [documents] changes.
     *
     * The previous book is dropped rather than `close()`d: a composition may
     * still be reading the old one while this swaps in the new, and UniFFI
     * registers a Cleaner that frees the Rust side once nothing refers to it.
     */
    private val _book = MutableStateFlow<RuleBook?>(null)
    val book: StateFlow<RuleBook?> = _book.asStateFlow()

    /**
     * Non-nil when the stored documents failed to load — which should be
     * impossible, since import validates before persisting, but a document could
     * still be invalidated by a core upgrade that removes a rule id.
     */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var loaded = false

    /** What an import added, for the confirmation message. */
    data class ImportSummary(val rules: Int, val tags: Int)

    /**
     * Read the stored documents and build the book. Idempotent, so every entry
     * point (a scan, the browser opening) can just call it.
     */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        _documents.value = load(context)
        rebuild()
    }

    /** The overlay handed to every scan. Empty means bundled defaults only. */
    fun parseOptions(context: Context): ParseOptions {
        ensureLoaded(context)
        return ParseOptions(
            ruleDocuments = _documents.value.map { it.toml },
            knownMerchants = emptyList(),
        )
    }

    // MARK: - Import

    /**
     * Validate [toml] by building a [RuleBook] from it, then persist.
     *
     * Validation is the core's, not ours: malformed TOML, an undeclared tag path,
     * and a `disables` naming an unknown rule id all come back as a
     * `ScanException`. Kotlin never parses TOML itself.
     *
     * Throws whatever the core threw, so the caller can show its message — it
     * names the offending tag path or rule id.
     */
    @Synchronized
    fun importDocument(context: Context, name: String, toml: String): ImportSummary {
        ensureLoaded(context)
        val before = _book.value
        val candidate = RuleBook(
            ParseOptions(
                ruleDocuments = _documents.value.map { it.toml } + toml,
                knownMerchants = emptyList(),
            ),
        )

        val addedRules = candidate.rules().count() - (before?.rules()?.count() ?: 0)
        val knownTags = (before?.tags() ?: emptyList()).map { it.path }.toSet()
        val addedTags = candidate.tags().count { it.path !in knownTags }

        _documents.value = _documents.value + ImportedRuleDocument(
            id = UUID.randomUUID().toString(),
            displayName = name,
            importedAt = System.currentTimeMillis(),
            toml = toml,
        )
        save(context)
        _book.value = candidate
        _loadError.value = null
        return ImportSummary(rules = addedRules, tags = addedTags)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        ensureLoaded(context)
        _documents.value = _documents.value.filterNot { it.id == id }
        save(context)
        rebuild()
    }

    // MARK: - Persistence

    private fun file(context: Context): File =
        File(ReceiptCaptureStore.directory(context), "item_rules.json")

    private fun load(context: Context): List<ImportedRuleDocument> {
        val f = file(context)
        val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONObject(text).getJSONArray("documents")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ImportedRuleDocument(
                    id = o.getString("id"),
                    displayName = o.getString("displayName"),
                    importedAt = o.getLong("importedAt"),
                    toml = o.getString("toml"),
                )
            }
        }.getOrElse {
            Log.e(TAG, "couldn't read stored rule documents", it)
            emptyList()
        }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        _documents.value.forEach { doc ->
            arr.put(
                JSONObject()
                    .put("id", doc.id)
                    .put("displayName", doc.displayName)
                    .put("importedAt", doc.importedAt)
                    .put("toml", doc.toml),
            )
        }
        runCatching { file(context).writeText(JSONObject().put("documents", arr).toString()) }
            .onFailure { Log.e(TAG, "couldn't persist rule documents", it) }
    }

    private fun rebuild() {
        val documents = _documents.value.map { it.toml }
        try {
            _book.value = RuleBook(ParseOptions(documents, emptyList()))
            _loadError.value = null
        } catch (t: Throwable) {
            // Fall back to the bundled corpus so the browser still renders and the
            // message explains why the user's rules are not applying.
            _book.value = runCatching { RuleBook(ParseOptions(emptyList(), emptyList())) }.getOrNull()
            _loadError.value = t.message ?: t.toString()
            Log.e(TAG, "rule corpus rejected; falling back to bundled defaults", t)
        }
    }

    private const val TAG = "ItemRuleStore"
}
