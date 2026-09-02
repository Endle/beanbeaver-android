package com.zhenbo.beanbeaver.receipt

import uniffi.bb_receipt_ffi.EditedItem
import uniffi.bb_receipt_ffi.ReceiptEdits
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

// The non-UI half of the Review & Fix screen: the identity a re-render has to
// preserve, the draft the screen edits, and the `ReceiptEdits` it sends. Kept out
// of `ReceiptEditorScreen` because none of it is view code — this is what decides
// whether an edited receipt is still the same receipt. Kotlin twin of iOS
// `ReceiptEditing.swift`.

// MARK: - Identity

/**
 * Recovering the image SHA-256 that a re-render has to be handed.
 *
 * `reformatReceipt` takes the hash as an argument rather than deriving it, and it
 * is not cosmetic: the hash is what produces `beanbeaver-id`, the `document:`
 * link, and the `beanbeaver-image-sha256` line. Pass null and all three vanish
 * from the re-rendered beancount — which would strand the record, because
 * `beanbeaver-id` is what [SpendStore] dedups new scans against and what
 * `markExported` matches an exported receipt by.
 *
 * Truncating is not an option either. The 8-char token in an existing
 * `beanbeaver-id` would reproduce the id and the document path, but the metadata
 * line carries the *full* hash, and a receipt whose stated sha256 is eight
 * characters followed by nothing is a false claim in someone's ledger.
 */
object ReceiptIdentity {
    private const val METADATA_KEY = "beanbeaver-image-sha256:"

    /**
     * The hash the previous render used, read back out of its own beancount.
     *
     * Preferred over re-hashing the photo because it is the value that was
     * actually used: it keeps the id stable even if the JPEG on disk has since
     * been re-encoded, and it works for a receipt whose photo the user cleared.
     */
    fun imageSha256(inBeancount: String): String? {
        for (line in inBeancount.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(METADATA_KEY)) continue
            return trimmed.removePrefix(METADATA_KEY).trim(' ', '\t', '"').ifEmpty { null }
        }
        return null
    }

    /**
     * Re-hash the capture. The fallback for a receipt parsed before the metadata
     * line existed, or one whose beancount was never rendered.
     */
    fun imageSha256(file: File): String? = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * The hash to hand `reformatReceipt` for [result], best source first.
     *
     * Null is a real answer, not a failure: a parse that never had an image hash
     * had no id or document link to preserve, so a re-render that also has none
     * is unchanged rather than degraded.
     */
    fun imageSha256(result: ReceiptResult, imageFile: File?): String? =
        imageSha256(result.beancount)
            ?: imageFile?.takeIf { it.exists() }?.let { imageSha256(it) }
}

// MARK: - Draft

/**
 * One line of the item block while it is being edited.
 *
 * Identified by an id minted here rather than by list position, so a row keeps
 * its identity — and its half-typed price — across an insert, a delete, or a
 * reorder that renumbers everything below it.
 */
data class EditedItemDraft(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    /**
     * Free text while typing, normalized on save. Held as the user typed it so a
     * half-entered "12." isn't rewritten under the cursor.
     */
    val price: String,
    val quantity: Int,
    /**
     * The tag path the user picked, or empty to let the rules classify
     * [description].
     *
     * Empty is the initial value for **every** row, including rows whose category
     * the parse already got right, and that is the point: core keeps the parse's
     * own classification for a line whose description is unchanged, so empty means
     * "leave this line's category alone" for an untouched row and "re-read it from
     * my new text" for a renamed one. Seeding it with the parsed path instead
     * would turn all of them into user overrides.
     */
    val tagPath: String = "",
    /**
     * What the parse classified this line as, for the row to show while [tagPath]
     * is empty. Null on a line the user added, which has no parse.
     */
    val parsedCategory: String? = null,
) {
    companion object {
        fun of(item: ReceiptItem) = EditedItemDraft(
            description = item.description,
            price = item.price,
            quantity = item.quantity,
            parsedCategory = item.tags.lastOrNull()?.display,
        )

        /**
         * A blank line for the user to fill in — the "add the row an orphaned
         * price belongs to" case.
         */
        fun blank() = EditedItemDraft(description = "", price = "", quantity = 1)
    }
}

/**
 * Everything the editor holds, and the [ReceiptEdits] it turns into.
 *
 * Split out of the view so the "did anything actually change?" question has one
 * answer. Every field of [ReceiptEdits] is "leave it alone" when absent, so the
 * draft sends only what the user touched — an edit to the date must not re-open
 * the item block, and an edit to one price must not restate the merchant.
 */
data class ReceiptEditDraft(
    val original: ReceiptResult,
    val merchant: String,
    /** Null when the receipt has no date and the user hasn't given it one. */
    val date: LocalDate?,
    val items: List<EditedItemDraft>,
    val total: String,
    val tax: String,
    val subtotal: String,
) {
    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun of(result: ReceiptResult) = ReceiptEditDraft(
            original = result,
            merchant = result.merchant,
            date = result.date?.let { runCatching { LocalDate.parse(it, ISO) }.getOrNull() },
            items = result.items.map { EditedItemDraft.of(it) },
            total = result.total,
            tax = result.tax ?: "",
            subtotal = result.subtotal ?: "",
        )

        /**
         * A typed amount as the decimal string core parses, or null when the
         * field is blank or unreadable. Normalizing here is what lets "$12.5",
         * "12.50 " and "12.5" all mean the same edit — and what stops a stray
         * currency symbol arriving as a parse error.
         */
        fun normalizedAmount(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val value = com.zhenbo.beanbeaver.ui.priceValue(trimmed) ?: return null
            return "%.2f".format(value)
        }
    }

    // MARK: Change detection

    val dateIso: String? get() = date?.format(ISO)

    val merchantChanged: Boolean get() = merchant.trim() != original.merchant

    val dateChanged: Boolean get() = dateIso != original.date

    /**
     * True when the block differs in shape or in any field. Compared against the
     * parse rather than tracked with a dirty flag, so an edit typed and then
     * undone is correctly not an edit.
     */
    val itemsChanged: Boolean
        get() {
            if (items.size != original.items.size) return true
            return items.zip(original.items).any { (draft, parsed) ->
                draft.description.trim() != parsed.description ||
                    normalizedAmount(draft.price) != normalizedAmount(parsed.price) ||
                    draft.quantity != parsed.quantity ||
                    draft.tagPath.isNotEmpty()
            }
        }

    val totalChanged: Boolean get() = changed(total, original.total)
    val taxChanged: Boolean get() = changed(tax, original.tax)
    val subtotalChanged: Boolean get() = changed(subtotal, original.subtotal)

    val hasChanges: Boolean
        get() = merchantChanged || dateChanged || itemsChanged ||
            totalChanged || taxChanged || subtotalChanged

    /**
     * A blank amount field means "leave what was parsed", not "set it to
     * nothing" — there is no way to *remove* a summary amount through
     * [ReceiptEdits], and a receipt that genuinely printed no tax is said with
     * `0.00` rather than by clearing the field.
     *
     * So an emptied field is deliberately **not** a change: reporting it as one
     * would light up Save for an edit that is then silently dropped on the way
     * out, since the field it would set can only be sent as a value.
     */
    private fun changed(value: String, parsed: String?): Boolean {
        val entered = normalizedAmount(value) ?: return false
        return entered != normalizedAmount(parsed ?: "")
    }

    // MARK: Validation

    /**
     * Why the item block can't be sent, or null when it can.
     *
     * Only the two things core would reject anyway, caught here so the message
     * names the row instead of arriving as a parse error about the receipt.
     */
    val itemProblem: String?
        get() {
            items.forEachIndexed { index, item ->
                val row = index + 1
                if (item.description.isBlank()) return "Item $row has no description."
                if (normalizedAmount(item.price) == null) {
                    return "Item $row (${item.description.trim()}) has no readable price."
                }
            }
            return null
        }

    // MARK: Arithmetic

    /** What the edited lines add up to — the figure that should meet the subtotal. */
    val itemsSum: Double
        get() = items.sumOf { com.zhenbo.beanbeaver.ui.priceValue(it.price) ?: 0.0 }

    /**
     * The receipt's own identity: `subtotal + tax = total`. Null when a field it
     * needs is blank or unreadable, since a check that can't be made shouldn't be
     * reported as a failure.
     */
    val summaryDifference: Double?
        get() {
            val t = com.zhenbo.beanbeaver.ui.priceValue(total) ?: return null
            val s = com.zhenbo.beanbeaver.ui.priceValue(subtotal) ?: return null
            val x = com.zhenbo.beanbeaver.ui.priceValue(tax) ?: 0.0
            return t - (s + x)
        }

    /** Difference between the item lines and the subtotal, when both are readable. */
    val itemsDifference: Double?
        get() {
            val s = com.zhenbo.beanbeaver.ui.priceValue(subtotal) ?: return null
            return itemsSum - s
        }

    // MARK: Output

    /** The edits to send, or null when nothing changed. */
    fun edits(): ReceiptEdits? {
        if (!hasChanges) return null
        return ReceiptEdits(
            merchant = if (merchantChanged) merchant.trim() else null,
            dateIso = if (dateChanged) dateIso else null,
            items = if (itemsChanged) items.map(::edited) else null,
            total = if (totalChanged) normalizedAmount(total) else null,
            tax = if (taxChanged) normalizedAmount(tax) else null,
            subtotal = if (subtotalChanged) normalizedAmount(subtotal) else null,
        )
    }

    private fun edited(draft: EditedItemDraft) = EditedItem(
        description = draft.description.trim(),
        price = normalizedAmount(draft.price) ?: draft.price.trim(),
        quantity = draft.quantity,
        tagPath = draft.tagPath,
    )
}
