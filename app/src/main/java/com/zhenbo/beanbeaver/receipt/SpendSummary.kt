package com.zhenbo.beanbeaver.receipt

import com.zhenbo.beanbeaver.ui.priceValue
import com.zhenbo.beanbeaver.ui.tagDisplay
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * One scanned receipt's persisted record: its parsed data, the state of its
 * photo, and whether it's reached an export target yet. This — not the receipt
 * total — is what the spending screen is computed from, and what the Receipts
 * list shows. See [SpendStore] for the store that owns these.
 *
 * Declared here rather than in [SpendStore] (where iOS keeps it) so this file
 * stays free of Android imports: [SpendSummary] is the one piece of this feature
 * that is pure arithmetic, and keeping its inputs pure is what lets the whole
 * thing be unit-tested on the JVM — and, if the two apps ever share logic, be
 * lifted into Rust without dragging a platform with it.
 */
data class SpendRecord(
    val id: String = UUID.randomUUID().toString(),
    val result: ReceiptResult,
    /** Epoch millis. */
    val scannedAt: Long,
    /**
     * Bare filename in the capture store, never a path — an absolute path goes
     * stale across reinstalls. Null when the capture write itself failed.
     */
    val captureFilename: String?,
    val wallMs: Double? = null,
    /**
     * Kept out of every spend total — returned, business, not mine. Scoped to the
     * spend figures only; the stored parse and what an export ships are untouched.
     */
    val isExcluded: Boolean = false,
    /**
     * Set when the *user* clears the photo, so "you cleared this" can be said
     * plainly and told apart from a file that went missing on its own.
     */
    val photoClearedAt: Long? = null,
    /** Set the first time this receipt reaches any export target, cleared never. */
    val exportedAt: Long? = null,
    /**
     * Which targets it has reached, de-duplicated ("GitHub", "Money Manager").
     * Plural because a receipt can legitimately go to both, and the row should
     * say which.
     */
    val exportedTargets: List<String> = emptyList(),
) {
    val isExported: Boolean get() = exportedAt != null

    /**
     * Three states, not two, because they read differently to a user and only one
     * of them is a problem — see [SpendStore.photoState].
     */
    enum class PhotoState { PRESENT, CLEARED, UNAVAILABLE }

    /**
     * What a row's status dot says. One state per receipt, not one per target:
     * *which* target it reached is detail-screen material ([exportedTargets]),
     * while the list only ever has to answer "is this filed yet".
     *
     * Two states, and [isExcluded] is deliberately not a third. Exclusion is
     * budget-scoped — it leaves the stored parse and everything an export ships
     * untouched — so an excluded receipt is still in the backlog and still goes
     * out with it. A grey "excluded" dot would sit on a row that the export bar
     * below is about to file, and under a chip counting it as unexported. The
     * exclusion is said in words in the row's subtitle instead, where it can't
     * be mistaken for status.
     */
    enum class ExportStatus(val label: String) {
        EXPORTED("Exported"),
        NOT_EXPORTED("Not exported"),
    }

    val exportStatus: ExportStatus
        get() = if (isExported) ExportStatus.EXPORTED else ExportStatus.NOT_EXPORTED
}

/**
 * The backlog: everything that hasn't reached a target yet. The one split the
 * status dots, the Receipts chips and the home card all draw, so they can't
 * disagree about what "not exported" means.
 *
 * These live on the list rather than on [SpendStore] (where iOS puts them)
 * because every screen here already collects `SpendStore.records` as state — a
 * computed property reading the store's backing field wouldn't recompose. Being
 * pure also keeps them JVM-testable, like the rest of this file.
 */
val List<SpendRecord>.unexported: List<SpendRecord> get() = filterNot { it.isExported }

val List<SpendRecord>.exported: List<SpendRecord> get() = filter { it.isExported }

/**
 * When anything last reached a target (epoch millis), or null if nothing ever
 * has. What the home card's status line dates itself by — "9 filed · last
 * export Mar 11" answers "am I up to date" in a way a bare count can't.
 */
val List<SpendRecord>.lastExportedAt: Long? get() = mapNotNull { it.exportedAt }.maxOrNull()

/**
 * Every target anything has reached, in first-seen order — "GitHub", "Money
 * Manager", or both. Read rather than assumed: the app really can file to more
 * than one place, so the status line names what actually happened instead of
 * whatever target happens to be selected now.
 */
val List<SpendRecord>.reachedTargets: List<String>
    get() = flatMap { it.exportedTargets }.distinct()

/**
 * Pure arithmetic over [SpendRecord]s — what a month of scanned receipts adds up
 * to, grouped the way the items were classified. Kotlin twin of iOS
 * `SpendSummary`.
 *
 * Computed fresh rather than cached, since the substrate (a few thousand records
 * at most) is cheap to re-scan. **No Android imports, no preference reads, no
 * view code**, so every figure here is checkable from a JVM unit test.
 *
 * **Every figure comes from `result.items`, not `result.total`.** A bank feed
 * already knows a Costco run was $148.73; only this app knows it was $54.45
 * grocery, $24.99 household and $58.40 gas. [Month.receiptTotal] is carried along
 * solely to reconcile against, never to spend from.
 */
object SpendSummary {

    /** One leaf category — the most specific label the classifier reached. */
    data class Leaf(
        val label: String,
        val amount: Double,
        val itemCount: Int,
    )

    /**
     * One top-level category and the leaves beneath it. The unit the spending
     * screen lists, so a month reads as "where the money went", largest first.
     */
    data class RootGroup(
        /** The raw root tag ("grocery") — matches the stored budget root. */
        val id: String,
        /**
         * The authored display label ("Grocery"), taken from the tag vocabulary
         * when it's available rather than capitalized here — the same reason
         * [tagDisplay] uses `display` verbatim.
         */
        val label: String,
        val amount: Double,
        val itemCount: Int,
        /** Largest first. */
        val leaves: List<Leaf>,
    )

    data class Month(
        val id: String,          // "2026-07"
        val label: String,       // "July 2026"
        /** The headline: every tracked item plus tax. What the month cost. */
        val tracked: Double,
        /** Items alone — what [roots] sums to, and [tracked] minus [tax]. */
        val itemsTotal: Double,
        /** Largest first, "Uncategorized" included so nothing scanned vanishes. */
        val roots: List<RootGroup>,
        val tax: Double,
        /** Sum of `result.total` — the reconciliation number. */
        val receiptTotal: Double,
        val receiptCount: Int,
        val excludedCount: Int,
        val unreadablePriceCount: Int,
        val records: List<SpendRecord>,
    ) {
        /**
         * The group for [root], or null when the month has no spend under it —
         * how the spending screen finds the one group a target applies to.
         */
        fun group(root: String): RootGroup? = roots.firstOrNull { it.id == root }

        /**
         * The largest single leaf anywhere in the month, so every category bar on
         * screen shares one scale and is actually comparable. Scaling per group
         * would put each root on its own invisible scale.
         */
        val maxLeafAmount: Double
            get() = roots.flatMap { it.leaves }.maxOfOrNull { it.amount } ?: 0.0

        /**
         * How far [tracked] sits from what the receipts themselves totalled, or
         * null when they agree. Non-null is normal rather than alarming: a scan
         * that reads every item but misses a `-5.00` discount line lands here, as
         * does one whose `total` didn't parse. The screen names it instead of
         * leaving the reader to subtract two numbers.
         */
        val unaccounted: Double?
            get() = (tracked - receiptTotal).takeIf { kotlin.math.abs(it) >= 0.01 }
    }

    // MARK: - Month bucketing

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val MONTH_ID = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)

    private fun monthLabelFormatter() = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    /**
     * The current calendar month's id — what a screen shows before anything has
     * been scanned into it.
     */
    fun currentMonthId(today: LocalDate = LocalDate.now()): String = today.format(MONTH_ID)

    /**
     * The calendar month a record belongs to: `result.date` unless it's missing or
     * a placeholder, in which case the row's own [SpendRecord.scannedAt] steps in
     * — mirroring `MoneyManagerExport.dateString`'s fallback, but with the row's
     * own scan time instead of "today", so a bucket can't drift with the clock on
     * a later run.
     */
    fun monthId(record: SpendRecord): String {
        val iso = record.result.date
        if (!record.result.dateIsPlaceholder && iso != null) {
            val parsed = runCatching { LocalDate.parse(iso, ISO) }.getOrNull()
            if (parsed != null) return parsed.format(MONTH_ID)
        }
        return Instant.ofEpochMilli(record.scannedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(MONTH_ID)
    }

    /** Every month with at least one record, newest first. */
    fun monthIds(records: List<SpendRecord>): List<String> =
        records.map(::monthId).distinct().sortedDescending()

    /**
     * The month a screen opens on: the newest one with receipts in it, falling
     * back to the current calendar month when there are none at all.
     *
     * Deliberately *not* "the current month": scanning happens in bursts, and a
     * screen that opens on a $0.00 October because the last receipt was in
     * September shows nothing and looks broken. Both the home card and the
     * spending screen route through this, so the number on the card is the month
     * tapping it lands on.
     */
    fun defaultMonthId(records: List<SpendRecord>): String =
        monthIds(records).firstOrNull() ?: currentMonthId()

    /** "2026-07" -> "July 2026", or [id] unchanged if it isn't a month id. */
    fun monthLabel(id: String): String =
        runCatching { YearMonth.parse(id, MONTH_ID).atDay(1).format(monthLabelFormatter()) }
            .getOrDefault(id)

    // MARK: - Classification

    /**
     * Sentinel root for items the classifier left untagged. Kept as a real group
     * rather than dropped, so the breakdown always reconciles against what was
     * actually scanned — same intent as `MoneyManagerExport.rows`.
     */
    const val UNCATEGORIZED_ROOT = "uncategorized"

    /**
     * The item's top-level category. The classifier emits tags broad→specific, so
     * the *first* tag carries the root. A path may itself be nested
     * ("grocery/meat"), hence the split.
     */
    private fun root(item: ReceiptItem): String =
        item.tags.firstOrNull()?.path?.substringBefore('/')?.takeIf { it.isNotEmpty() }
            ?: UNCATEGORIZED_ROOT

    /**
     * The authored label for a root, when this item's tag list carries the root
     * tag itself — the vocabulary's own wording beats capitalizing a raw path
     * segment ("personalcare" -> "Personal Care", not "Personalcare").
     */
    private fun rootLabel(item: ReceiptItem, root: String): String? =
        item.tags.firstOrNull { it.path == root && it.display.isNotEmpty() }?.display

    /**
     * The item's display leaf — the app's existing label ([tagDisplay]), so
     * spending, the result card and the Money Manager export agree by
     * construction.
     *
     * Not private: the drill-down groups by the same label the totals were
     * accumulated under, so it can't disagree with the figure that was tapped to
     * reach it.
     */
    fun leafLabel(item: ReceiptItem): String =
        tagDisplay(item.tags).primary ?: "Uncategorized"

    // MARK: - Drill-down

    /**
     * One line item, with the receipt it came from. What a category total is
     * actually made of — tapping "Dairy $19.38" asks *which items*, and a receipt
     * list would answer with whole-receipt totals that have nothing to do with the
     * number tapped.
     */
    data class ItemEntry(
        /**
         * Stable within a month: a record's id plus the item's index in it. Two
         * identical lines on one receipt stay distinct rows.
         */
        val id: String,
        val item: ReceiptItem,
        val record: SpendRecord,
        val amount: Double,
    )

    /**
     * One receipt's contribution to a category: the items of it that landed under
     * the tapped category, and the receipt they were printed on.
     *
     * The unit the drill-down lists, because a category total is spread over
     * *purchases* — "$8.42 of this Costco run was dairy" is the shape of the
     * answer, and repeating the merchant on every item row buries it.
     *
     * Derived from [items] rather than accumulated separately: one matching
     * predicate means a group can't disagree with the flat list, or with the
     * figure that was tapped to reach it.
     */
    data class ReceiptGroup(
        val record: SpendRecord,
        /** The matching items, in the order they were printed. */
        val entries: List<ItemEntry>,
        /**
         * What those items add up to — this receipt's share of the category total.
         * [entries] sums to this, and every group sums to the category.
         */
        val amount: Double,
        /**
         * The whole receipt's total, or null when `result.total` didn't parse.
         * Carried as context only — never spent from, per this type's header.
         */
        val receiptTotal: Double?,
    )

    /**
     * What a category is selected by — a whole top-level group, or one leaf inside
     * it. The two cases exist because tapping a card's header and tapping a row in
     * it are different questions.
     *
     * A root is selected by its **raw tag id**, not its display label: the group's
     * label is whatever authored wording any of its items supplied
     * ("personalcare" -> "Personal Care"), so matching on the label would drop
     * every item in the group that didn't carry the root tag itself. A leaf
     * carries no such id — [leafLabel] is the only thing it was ever accumulated
     * under — so it matches on the label it was grouped by.
     */
    sealed interface Category {
        data class Root(val id: String) : Category
        data class Leaf(val label: String) : Category
    }

    /**
     * Every item in [records] under [category], newest receipt first, and within a
     * receipt in the order the items were printed.
     *
     * Recomputed from the month's records rather than stored during accumulation:
     * the substrate is small, and deriving it here means the list and the total
     * can't drift apart. Excluded receipts are left out, matching every other
     * figure on the spending screen.
     */
    fun items(category: Category, records: List<SpendRecord>): List<ItemEntry> = buildList {
        for (record in records) {
            if (record.isExcluded) continue
            record.result.items.forEachIndexed { index, item ->
                val matches = when (category) {
                    is Category.Root -> root(item) == category.id
                    is Category.Leaf -> leafLabel(item) == category.label
                }
                if (!matches) return@forEachIndexed
                add(
                    ItemEntry(
                        id = "${record.id}-$index",
                        item = item,
                        record = record,
                        amount = priceValue(item.price) ?: 0.0,
                    ),
                )
            }
        }
    }

    /**
     * [items], grouped by the receipt each item was printed on.
     *
     * Newest receipt first, and within a receipt the printed order — both
     * inherited rather than re-sorted: [items] walks [records] in store order
     * (newest-first) and each receipt's items in index order, so accumulating in
     * first-seen order preserves both.
     */
    fun receipts(category: Category, records: List<SpendRecord>): List<ReceiptGroup> {
        val grouped = LinkedHashMap<String, MutableList<ItemEntry>>()
        for (entry in items(category, records)) {
            grouped.getOrPut(entry.record.id) { mutableListOf() }.add(entry)
        }
        return grouped.values.map { entries ->
            ReceiptGroup(
                record = entries.first().record,
                entries = entries,
                amount = entries.sumOf { it.amount },
                receiptTotal = priceValue(entries.first().record.result.total),
            )
        }
    }

    // MARK: - Arithmetic

    /**
     * Insertion-ordered accumulation so ties in amount keep a stable order through
     * the largest-first sorts.
     */
    private class RootAccumulator(var label: String) {
        var amount = 0.0
        var itemCount = 0
        val leaves = LinkedHashMap<String, Pair<Double, Int>>()

        fun add(leaf: String, value: Double) {
            amount += value
            itemCount += 1
            val (sum, count) = leaves[leaf] ?: (0.0 to 0)
            leaves[leaf] = (sum + value) to (count + 1)
        }
    }

    fun month(id: String, records: List<SpendRecord>): Month {
        val monthRecords = records.filter { monthId(it) == id }
        val excludedCount = monthRecords.count { it.isExcluded }
        val tracked = monthRecords.filterNot { it.isExcluded }

        var itemsTotal = 0.0
        var tax = 0.0
        var receiptTotal = 0.0
        var unreadablePriceCount = 0
        val rootTotals = LinkedHashMap<String, RootAccumulator>()

        for (record in tracked) {
            val result: ReceiptResult = record.result
            receiptTotal += priceValue(result.total) ?: 0.0
            priceValue(result.tax)?.let { tax += it }
            for (item in result.items) {
                // An unreadable price is counted and carried at zero rather than
                // dropped: the item still happened, and the footer says how many
                // couldn't be read.
                val parsed = priceValue(item.price)
                if (parsed == null) unreadablePriceCount += 1
                val amount = parsed ?: 0.0
                itemsTotal += amount

                val rootId = root(item)
                val acc = rootTotals.getOrPut(rootId) {
                    RootAccumulator(
                        if (rootId == UNCATEGORIZED_ROOT) {
                            "Uncategorized"
                        } else {
                            rootId.replaceFirstChar { it.uppercase() }
                        },
                    )
                }
                rootLabel(item, rootId)?.let { acc.label = it }
                acc.add(leafLabel(item), amount)
            }
        }

        val roots = rootTotals
            .map { (rootId, acc) ->
                RootGroup(
                    id = rootId,
                    label = acc.label,
                    amount = acc.amount,
                    itemCount = acc.itemCount,
                    leaves = acc.leaves
                        .map { (label, v) -> Leaf(label = label, amount = v.first, itemCount = v.second) }
                        .sortedByDescending { it.amount },
                )
            }
            .sortedByDescending { it.amount }

        return Month(
            id = id,
            label = monthLabel(id),
            tracked = itemsTotal + tax,
            itemsTotal = itemsTotal,
            roots = roots,
            tax = tax,
            receiptTotal = receiptTotal,
            receiptCount = tracked.size,
            excludedCount = excludedCount,
            unreadablePriceCount = unreadablePriceCount,
            records = monthRecords,
        )
    }
}
