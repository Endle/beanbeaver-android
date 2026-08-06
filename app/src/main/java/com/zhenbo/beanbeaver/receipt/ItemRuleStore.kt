package com.zhenbo.beanbeaver.receipt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.ParseOptions
import uniffi.bb_receipt_ffi.RuleBook
import java.io.File
import java.util.UUID

/**
 * A rule document the user brought in, stored verbatim.
 *
 * The TOML text is **copied**, not referenced by a document-picker URI: rules
 * have to keep working after the source file is moved, renamed, or deleted.
 */
data class ImportedRuleDocument(
    val id: String,
    /** Filename it was imported from, shown in the list. */
    val displayName: String,
    val importedAt: Long,
    /** The document itself — what gets handed to the core as an override layer. */
    val toml: String,
)

/**
 * Owns the user's imported rule documents and the `RuleBook` they produce.
 * Kotlin twin of iOS `ItemRuleStore`.
 *
 * Two responsibilities that are easy to conflate: this is the *only* thing that
 * persists user rules, and it is also what the scan path reads to build
 * [parseOptions]. Keeping both here is what stops the browser from showing one
 * ruleset while scans use another.
 */
object ItemRuleStore {
    private const val FILE_NAME = "item_rules.json"

    private var documents: List<ImportedRuleDocument> = emptyList()

    /** The rule corpus currently in force: bundled defaults plus every imported
     *  document, later ones winning. Rebuilt whenever [documents] changes. */
    private var book: RuleBook? = null

    /** Non-null when the stored documents failed to load — which should be
     *  impossible, since import validates before persisting, but a document could
     *  still be invalidated by a core upgrade that removes a rule id. */
    private var loadError: String? = null

    private var loaded = false

    val isLoaded: Boolean get() = loaded

    /** The rule corpus the browser renders. Null only before [ensureLoaded]. */
    val ruleBook: RuleBook? get() = book

    val documentsList: List<ImportedRuleDocument> get() = documents

    val currentLoadError: String? get() = loadError

    /** Load once. Called from the browser and from the scan path; both are
     *  cheap after the first, so it is safe to call on every scan. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        load(context)
        rebuild(context)
        loaded = true
    }

    /** The overlay handed to every scan. Empty means bundled defaults only. */
    fun parseOptions(): ParseOptions = ParseOptions(
        ruleDocuments = documents.map { it.toml },
        knownMerchants = emptyList(),
    )

    /** What a successful import added, for the confirmation message. */
    data class ImportSummary(val rules: Int, val tags: Int)

    /**
     * Validate [toml] by building a `RuleBook` from it, then persist.
     *
     * Validation is the core's, not ours: malformed TOML, an undeclared tag
     * path, and a `disables` naming an unknown rule id all throw. The error is
     * surfaced verbatim, since it names the offending path or id.
     *
     * Throws [RuleImportException] on a bad document.
     */
    fun importDocument(context: Context, name: String, toml: String): ImportSummary {
        ensureLoaded(context)
        val before = book
        val candidate = runCatching {
            RuleBook(ParseOptions(documents.map { it.toml } + toml, emptyList()))
        }.getOrElse { throw RuleImportException(it.message ?: it.toString()) }

        val addedRules = candidate.rules().size - (before?.rules()?.size ?: 0)
        val knownTags = (before?.tags() ?: emptyList()).map { it.path }.toSet()
        val addedTags = candidate.tags().count { it.path !in knownTags }

        documents = documents + ImportedRuleDocument(
            id = UUID.randomUUID().toString(),
            displayName = name,
            importedAt = System.currentTimeMillis(),
            toml = toml,
        )
        save(context)
        book = candidate
        loadError = null
        return ImportSummary(rules = addedRules, tags = addedTags)
    }

    fun remove(context: Context, id: String) {
        ensureLoaded(context)
        documents = documents.filterNot { it.id == id }
        save(context)
        rebuild(context)
    }

    /** The core rejected a rule document — see [importDocument]. */
    class RuleImportException(message: String) : Exception(message)

    // MARK: - Persistence

    private fun file(context: Context): File =
        File(ReceiptCaptureStore.directory(context), FILE_NAME)

    private fun load(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("documents")
            documents = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                ImportedRuleDocument(
                    id = o.getString("id"),
                    displayName = o.getString("displayName"),
                    importedAt = o.getLong("importedAt"),
                    toml = o.getString("toml"),
                )
            }
        }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        documents.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("displayName", d.displayName)
                    .put("importedAt", d.importedAt)
                    .put("toml", d.toml),
            )
        }
        runCatching {
            file(context).writeText(JSONObject().put("documents", arr).toString(2))
        }
    }

    private fun rebuild(context: Context) {
        val result = runCatching { RuleBook(parseOptions()) }
        if (result.isSuccess) {
            book = result.getOrNull()
            loadError = null
        } else {
            // Fall back to the bundled corpus so the browser still renders and
            // the message explains why the user's rules are not applying.
            book = runCatching { RuleBook(ParseOptions(emptyList(), emptyList())) }.getOrNull()
            loadError = result.exceptionOrNull()?.message ?: "Failed to build the ruleset."
        }
    }
}
