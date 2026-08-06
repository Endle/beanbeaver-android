package com.zhenbo.beanbeaver.receipt

import com.zhenbo.beanbeaver.ui.priceValue
import com.zhenbo.beanbeaver.ui.tagDisplay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Pure arithmetic over [SpendRecord]s — what a month of scanned receipts adds up
 * to, grouped the way the items were classified. Computed fresh rather than
 * cached, since the substrate (a few thousand records at most) is cheap to
 * re-scan. No view code and no preference reads, so it's checkable by hand.
 * Kotlin twin of iOS `SpendSummary`.
 *
 * **Every figure comes from `result.items`, not `result.total`.** A bank feed
 * already knows a Costco run was $148.73; only this app knows it was $54.45
 * grocery, $24.99 household and $58.40 gas. `receiptTotal` is carried along
 * solely to reconcile against, never to spend from.
 */
object SpendSummary {

    /** One leaf category — the most specific label the classifier reached. */
    data class Leaf(val id: String, val label: String, val amount: Double, val itemCount: Int)

    /** One top-level category and the leaves beneath it. The unit the spending
     *  screen lists, so a month reads as "where the money went", largest first. */
    data class RootGroup(
        /** The raw root tag (`"grocery"`) — matches `BudgetPrefs.root`, which is
         *  how the one group carrying a target is found. */
        val id: String,
        /** The authored display label (`"Grocery"`), taken from the tag
         *  vocabulary when it's available rather than capitalized here. */
        val label: String,
        val amount: Double,
        val itemCount: Int,
        /** Largest first. */
        val leaves: List<Leaf>,
    )

    data class Month(
        val id: String,                  // "2026-07"
        val label: String,               // "July 2026"
        val tracked: Double,             // every tracked item plus tax — the headline
        val itemsTotal: Double,          // items alone — what `roots` sums to
        val roots: List<RootGroup>,      // largest first, "Uncategorized" included
        val tax: Double,
        val receiptTotal: Double,        // sum of result.total — the reconciliation number
        val receiptCount: Int,
        val excludedCount: Int,
        val unreadablePriceCount: Int,
        val records: List<SpendRecord>,
    ) {
        /** The group for [root], or null when the month has no spend under it. */
        fun group(root: String): RootGroup? = roots.firstOrNull { it.id == root }

        /** The largest single leaf anywhere in the month, so every category bar
         *  on screen shares one scale and is actually comparable. */
        val maxLeafAmount: Double get() = roots.flatMap { it.leaves }.maxOfOrNull { it.amount } ?: 0.0

        /** How far [tracked] sits from what the receipts themselves totalled, or
         *  null when they agree. Non-null is normal rather than alarming: a scan
         *  that reads every item but misses a `-5.00` discount line lands here. */
        val unaccounted: Double? get() {
            val gap = tracked - receiptTotal
            return if (abs(gap) >= 0.01) gap else null
        }
    }

    // MARK: - Month bucketing

    private val monthIdFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    private val monthLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    /** The current calendar month's id — what a screen shows before anything has
     *  been scanned into it. */
    fun currentMonthId(): String = LocalDate.now().format(monthIdFormatter)

    /** The calendar month a record belongs to: `result.date` unless it's missing
     *  or a placeholder, in which case the row's own [SpendRecord.scannedAt]
     *  steps in — so a bucket can't drift with the clock on a later run. */
    fun monthId(for record: SpendRecord): String {
        val parsed = if (!record.result.dateIsPlaceholder && record.result.date != null) {
            runCatching { LocalDate.parse(record.result.date!!) }.getOrNull()
        } else null
        val date = parsed ?: Instant.ofEpochMilli(record.scannedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(monthIdFormatter)
    }

    /** Every month with at least one record, newest first. */
    fun monthIds(from records: List<SpendRecord>): List<String> {
        val seen = LinkedHashSet<String>()
        records.forEach { seen.add(monthId(for = it)) }
        return seen.sortedDescending()
    }

    /** The month a screen opens on: the newest one with receipts in it, falling
     *  back to the current calendar month when there are none at all. */
    fun defaultMonthId(from records: List<SpendRecord>): String =
        monthIds(from = records).firstOrNull() ?: currentMonthId()

    /** "2026-07" -> "July 2026", or [id] unchanged if it isn't a month id. */
    fun monthLabel(for id: String): String =
        runCatching { LocalDate.parse("$id-01").format(monthLabelFormatter) }.getOrDefault(id)

    // MARK: - Classification

    /** Sentinel root for items the classifier left untagged. Kept as a real group
     *  rather than dropped, so the breakdown always reconciles against what was
     *  actually scanned. */
    const val UNCATEGORIZED_ROOT = "uncategorized"

    /** The item's top-level category. The classifier emits tags broad→specific
     *  (`["grocery", "meat", "chicken"]`), so the *first* tag carries the root.
     *  A path may itself be nested (`"grocery/meat"`), hence the split. */
    private fun root(of item: uniffi.bb_receipt_ffi.ReceiptItem): String {
        val first = item.tags.firstOrNull() ?: return UNCATEGORIZED_ROOT
        return first.path.substringBefore("/").ifEmpty { UNCATEGORIZED_ROOT }
    }

    /** The authored label for a root, when this item's tag list carries the root
     *  tag itself — the vocabulary's own wording beats capitalizing a raw path
     *  segment (`"personalcare"` -> `"Personal Care"`, not `"Personalcare"`). */
    private fun rootLabel(of item: uniffi.bb_receipt_ffi.ReceiptItem, root: String): String? =
        item.tags.firstOrNull { it.path == root && it.display.isNotEmpty() }?.display

    /** The item's display leaf — the app's existing label (`tagDisplay`), so
     *  spending, the result card and the Money Manager export agree by
     *  construction. Not private: the drill-down groups by the same label the
     *  totals were accumulated under, so it can't disagree with the figure that
     *  was tapped to reach it. */
    fun leafLabel(of item: uniffi.bb_receipt_ffi.ReceiptItem): String =
        tagDisplay(item.tags).primary ?: "Uncategorized"

    // MARK: - Drill-down

    /** One line item, with the receipt it came from. What a category total is
     *  actually made of. */
    data class ItemEntry(
        /** Stable within a month: a record's id plus the item's index in it.
         *  Two identical lines on one receipt stay distinct rows. */
        val id: String,
        val item: uniffi.bb_receipt_ffi.ReceiptItem,
        val record: SpendRecord,
        val amount: Double,
    )

    /** One receipt's contribution to a category: the items of it that landed
     *  under the tapped category, and the receipt they were printed on. Derived
     *  from [items] rather than accumulated separately, so a group can't disagree
     *  with the flat list, or with the figure that was tapped to reach it. */
    data class ReceiptGroup(
        val record: SpendRecord,
        val entries: List<ItemEntry>,
        /** What those items add up to — this receipt's share of the category
         *  total. `entries` sums to this, and every group sums to the category. */
        val amount: Double,
        /** The whole receipt's total, or null when `result.total` didn't parse. */
        val receiptTotal: Double?,
    ) {
        val id: String get() = record.id
    }

    /** What a category is selected by — a whole top-level group, or one leaf
     *  inside it. A root is selected by its **raw tag id**, not its display
     *  label; a leaf carries no such id, so it matches on the label it was
     *  grouped by. */
    sealed interface Category {
        data class Root(val id: String) : Category
        data class Leaf(val label: String) : Category
    }

    /** Every item in [records] under [category], newest receipt first, and within
     *  a receipt in the order the items were printed. Excluded receipts are left
     *  out, matching every other figure on the spending screen. */
    fun items(category: Category, from records: List<SpendRecord>): List<ItemEntry> {
        val entries = mutableListOf<ItemEntry>()
        for (record in records) {
            if (record.isExcluded) continue
            record.result.items.forEachIndexed { index, item ->
                val matches = when (category) {
                    is Category.Root -> root(of = item) == category.id
                    is Category.Leaf -> leafLabel(of = item) == category.label
                }
                if (!matches) return@forEachIndexed
                entries.add(
                    ItemEntry(
                        id = "${record.id}-$index",
                        item = item,
                        record = record,
                        amount = priceValue(item.price) ?: 0.0,
                    ),
                )
            }
        }
        return entries
    }

    /** [items], grouped by the receipt each item was printed on. Newest receipt
     *  first, and within a receipt the printed order — both inherited rather than
     *  re-sorted: [items] walks [records] in store order (newest-first) and each
     *  receipt's items in order, so accumulating in first-seen order preserves
     *  both. */
    fun receipts(category: Category, from records: List<SpendRecord>): List<ReceiptGroup> {
        val order = mutableListOf<String>()
        val grouped = LinkedHashMap<String, MutableList<ItemEntry>>()
        for (entry in items(category, from = records)) {
            if (!grouped.containsKey(entry.record.id)) order.add(entry.record.id)
            grouped.getOrPut(entry.record.id) { mutableListOf() }.add(entry)
        }
        return order.mapNotNull { id ->
            val record = records.firstOrNull { it.id == id } ?: return@mapNotNull null
            val entries = grouped[id] ?: return@mapNotNull null
            ReceiptGroup(
                record = record,
                entries = entries,
                amount = entries.sumOf { it.amount },
                receiptTotal = priceValue(record.result.total),
            )
        }
    }

    // MARK: - Arithmetic

    private class RootAccumulator(val id: String) {
        var label: String = id.ifEmpty { UNCATEGORIZED_ROOT }.replaceFirstChar { it.uppercase() }
        var amount: Double = 0.0
        var itemCount: Int = 0
        val leaves = LinkedHashMap<String, Pair<Double, Int>>()

        fun add(leaf: String, value: Double) {
            amount += value
            itemCount += 1
            val (sum, count) = leaves[leaf] ?: (0.0 to 0)
            leaves[leaf] = (sum + value) to (count + 1)
        }
    }

    fun month(id: String, from records: List<SpendRecord>): Month {
        val monthRecords = records.filter { monthId(for = it) == id }
        val excludedCount = monthRecords.count { it.isExcluded }
        val tracked = monthRecords.filter { !it.isExcluded }

        var itemsTotal = 0.0
        var tax = 0.0
        var receiptTotal = 0.0
        var unreadablePriceCount = 0
        val rootOrder = mutableListOf<String>()
        val rootTotals = LinkedHashMap<String, RootAccumulator>()

        for (record in tracked) {
            val result = record.result
            receiptTotal += priceValue(result.total) ?: 0.0
            result.tax?.let { tax += priceValue(it) ?: 0.0 }
            for (item in result.items) {
                // An unreadable price is counted and carried at zero rather than
                // dropped: the item still happened, and the footer says how many
                // couldn't be read.
                val parsed = priceValue(item.price)
                if (parsed == null) unreadablePriceCount++
                val amount = parsed ?: 0.0
                itemsTotal += amount

                val rootId = root(of = item)
                if (!rootTotals.containsKey(rootId)) {
                    rootOrder.add(rootId)
                    rootTotals[rootId] = RootAccumulator(rootId)
                }
                val acc = rootTotals[rootId]!!
                if (rootId == UNCATEGORIZED_ROOT) acc.label = "Uncategorized"
                rootLabel(of = item, root = rootId)?.let { acc.label = it }
                acc.add(leafLabel(of = item), amount)
            }
        }

        val roots = rootOrder.mapNotNull { rootId ->
            val acc = rootTotals[rootId] ?: return@mapNotNull null
            val leaves = acc.leaves.entries
                .sortedByDescending { it.value.first }
                .map { (label, pair) -> Leaf(id = label, label = label, amount = pair.first, itemCount = pair.second) }
            RootGroup(
                id = rootId,
                label = acc.label,
                amount = acc.amount,
                itemCount = acc.itemCount,
                leaves = leaves,
            )
        }.sortedByDescending { it.amount }

        return Month(
            id = id,
            label = monthLabel(for = id),
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
