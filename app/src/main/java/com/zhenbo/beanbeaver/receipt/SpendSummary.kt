package com.zhenbo.beanbeaver.receipt

import uniffi.bb_mobile_ffi.SpendCategory
import uniffi.bb_mobile_ffi.SpendDate
import uniffi.bb_mobile_ffi.SpendInput
import uniffi.bb_mobile_ffi.SpendItem
import uniffi.bb_mobile_ffi.SpendItemEntry
import uniffi.bb_mobile_ffi.SpendMonthFacts
import uniffi.bb_mobile_ffi.SpendTag
import uniffi.bb_mobile_ffi.SpendTrend
import uniffi.bb_mobile_ffi.SpendWeekday
import uniffi.bb_mobile_ffi.spendCurrentMonthId
import uniffi.bb_mobile_ffi.spendDefaultMonthId
import uniffi.bb_mobile_ffi.spendItems
import uniffi.bb_mobile_ffi.spendLeafLabel
import uniffi.bb_mobile_ffi.spendMonth
import uniffi.bb_mobile_ffi.spendMonthFacts
import uniffi.bb_mobile_ffi.spendMonthId
import uniffi.bb_mobile_ffi.spendMonthIds
import uniffi.bb_mobile_ffi.spendMonthLabel
import uniffi.bb_mobile_ffi.spendReceiptGroups
import uniffi.bb_mobile_ffi.spendTrend
import uniffi.bb_mobile_ffi.spendUncategorizedRoot
import uniffi.bb_receipt_ffi.ItemTag
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
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
     * totals-scoped — it leaves the stored parse and everything an export ships
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
 * What a month of scanned receipts adds up to, grouped the way the items were
 * classified.
 *
 * **The arithmetic itself is no longer here.** It lives in the shared Rust
 * crate `spend-core` (beanbeaver-mobile-util), reached through the
 * `bb_mobile_ffi` UniFFI namespace, so this app and `beanbeaver-ios` compute
 * spending from one implementation instead of two hand-synced ports. What
 * remains in this file is the part that is genuinely Android's:
 *
 *  - **the projection** — [SpendRecord] down to the slim [SpendInput] Rust
 *    reads, including resolving `scannedAt` to a local calendar date, which
 *    needs a timezone database Rust deliberately does not carry;
 *  - **re-attachment** — Rust identifies a receipt by id and an item by index,
 *    so the types below hand back the app's own [SpendRecord] / [ReceiptItem]
 *    objects that the screens draw from.
 *
 * The public surface is unchanged, so no screen had to move. Computed fresh
 * rather than cached, as before.
 *
 * **Every figure comes from `result.items`, not `result.total`.** A bank feed
 * already knows a Costco run was $148.73; only this app knows it was $54.45
 * grocery, $24.99 household and $58.40 gas. [Month.receiptTotal] is carried along
 * solely to reconcile against, never to spend from.
 *
 * The behaviour is pinned by `spend-core`'s 28 Rust tests rather than by the JVM
 * tests that used to live in `SpendSummaryTest.kt`; what that file still covers
 * is the projection and re-attachment below, which are this side's own risk.
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
        /**
         * The raw root tag ("grocery"), not the display label — what a
         * [Category.Root] is selected by, and what the Spending screen's
         * trend chips carry.
         */
        val id: String,
        /** The authored display label ("Grocery"), from the tag vocabulary. */
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
        /** Includes excluded rows: the Receipts list still shows them. */
        val records: List<SpendRecord>,
        /**
         * The largest single leaf anywhere in the month, so every category bar on
         * screen shares one scale and is actually comparable.
         */
        val maxLeafAmount: Double,
        /**
         * How far [tracked] sits from what the receipts themselves totalled, or
         * null when they agree. Non-null is normal rather than alarming: a scan
         * that reads every item but misses a `-5.00` discount line lands here.
         */
        val unaccounted: Double?,
    ) {
        /**
         * The group for [root], or null when the month has no spend under it —
         * how the spending screen finds the one group a target applies to.
         */
        fun group(root: String): RootGroup? = roots.firstOrNull { it.id == root }
    }

    /**
     * One line item, with the receipt it came from. What a category total is
     * actually made of — tapping "Dairy $19.38" asks *which items*.
     */
    data class ItemEntry(
        /** Stable within a month: a record's id plus the item's index in it. */
        val id: String,
        val item: ReceiptItem,
        val record: SpendRecord,
        val amount: Double,
    )

    /**
     * One receipt's contribution to a category: the items of it that landed under
     * the tapped category, and the receipt they were printed on.
     */
    data class ReceiptGroup(
        val record: SpendRecord,
        /** The matching items, in the order they were printed. */
        val entries: List<ItemEntry>,
        /** This receipt's share of the category total. */
        val amount: Double,
        /** The whole receipt's total, or null when it didn't parse. Context only. */
        val receiptTotal: Double?,
    )

    /**
     * What a category is selected by — a whole top-level group, or one leaf
     * inside it. A root is selected by its **raw tag id**, not its display
     * label: matching on the label would drop every item in the group that
     * didn't itself carry the root tag.
     */
    sealed interface Category {
        data class Root(val id: String) : Category
        data class Leaf(val label: String) : Category
    }

    /** Sentinel root for items the classifier left untagged. */
    val UNCATEGORIZED_ROOT: String by lazy { spendUncategorizedRoot() }

    // MARK: - Month bucketing

    /** The current calendar month's id. */
    fun currentMonthId(today: LocalDate = LocalDate.now()): String =
        spendCurrentMonthId(today.toFfi())

    /**
     * The calendar month a record belongs to: `result.date` unless it's missing
     * or a placeholder, in which case the row's own scan date steps in.
     */
    fun monthId(record: SpendRecord): String = spendMonthId(record.toFfi())

    /** Every month with at least one record, newest first. */
    fun monthIds(records: List<SpendRecord>): List<String> =
        spendMonthIds(records.map { it.toFfi() })

    /**
     * The month a screen opens on: the newest one with receipts in it, falling
     * back to the current calendar month when there are none at all.
     *
     * Deliberately *not* "the current month": scanning happens in bursts, and a
     * screen that opens on a $0.00 October because the last receipt was in
     * September shows nothing and looks broken.
     */
    fun defaultMonthId(records: List<SpendRecord>): String =
        spendDefaultMonthId(records.map { it.toFfi() }, LocalDate.now().toFfi())

    /** "2026-07" -> "July 2026", or [id] unchanged if it isn't a month id. */
    fun monthLabel(id: String): String = spendMonthLabel(id)

    /**
     * The item's display leaf.
     *
     * **Twin of [tagDisplay]'s `primary`, and they must not drift** — the
     * spending screen groups by this while the result card labels by
     * `tagDisplay`, so a divergence shows up as one item filed under two names.
     * The rule now lives in Rust (`spend_core::leaf_label`); this delegates
     * rather than reimplementing so there is only one place to change.
     */
    fun leafLabel(item: ReceiptItem): String = spendLeafLabel(item.tags.map { it.toFfi() })

    // MARK: - Drill-down

    /**
     * Every item in [records] under [category], newest receipt first, and within
     * a receipt in the order the items were printed. Excluded receipts are left
     * out, matching every other figure on the spending screen.
     */
    fun items(category: Category, records: List<SpendRecord>): List<ItemEntry> {
        val byId = records.associateBy { it.id }
        return spendItems(category.toFfi(), records.map { it.toFfi() })
            .mapNotNull { it.reattach(byId) }
    }

    /** [items], grouped by the receipt each item was printed on. */
    fun receipts(category: Category, records: List<SpendRecord>): List<ReceiptGroup> {
        val byId = records.associateBy { it.id }
        return spendReceiptGroups(category.toFfi(), records.map { it.toFfi() })
            .mapNotNull { group ->
                val record = byId[group.recordId] ?: return@mapNotNull null
                ReceiptGroup(
                    record = record,
                    entries = group.entries.mapNotNull { it.reattach(byId) },
                    amount = group.amount,
                    receiptTotal = group.receiptTotal,
                )
            }
    }

    // MARK: - Arithmetic

    fun month(id: String, records: List<SpendRecord>): Month {
        val byId = records.associateBy { it.id }
        val m = spendMonth(id, records.map { it.toFfi() })
        return Month(
            id = m.id,
            label = m.label,
            tracked = m.tracked,
            itemsTotal = m.itemsTotal,
            roots = m.roots.map { root ->
                RootGroup(
                    id = root.id,
                    label = root.label,
                    amount = root.amount,
                    itemCount = root.itemCount.toInt(),
                    leaves = root.leaves.map { Leaf(it.label, it.amount, it.itemCount.toInt()) },
                )
            },
            tax = m.tax,
            receiptTotal = m.receiptTotal,
            receiptCount = m.receiptCount.toInt(),
            excludedCount = m.excludedCount.toInt(),
            unreadablePriceCount = m.unreadablePriceCount.toInt(),
            records = m.recordIds.mapNotNull { byId[it] },
            maxLeafAmount = m.maxLeafAmount,
            unaccounted = m.unaccounted,
        )
    }

    // MARK: - Trend

    /**
     * **The weekly trend surfaces are on, and the surface they came back to is
     * not the one that was withheld.**
     *
     * iOS turned these off on 2026-08-19 after seeing them against real receipts:
     * they drew what they claimed to draw, but six weekly totals were not the
     * information worth a third of the home card. That was a product answer, not
     * a defect — **don't go looking for a bug here.**
     *
     * Back on 2026-08-21 as six **bars** — discrete weekly totals, the newest
     * visibly partial — with the delta as the card's own header figure and
     * nothing repeating it. One flag rather than three comment blocks, so home
     * and the Spending card come back together.
     */
    const val SHOW_WEEKLY_TREND = true

    /**
     * How many weeks the charts plot. Six is what the design asks for and what
     * fits the card's width at a legible bar spacing.
     */
    const val TREND_WEEKS: UInt = 6u

    /** The rolling window behind the second figure beside a month total. */
    const val ROLLING_DAYS: UInt = 30u

    /**
     * The two figures the home slip prints under a month's total, and the windows
     * they cover.
     *
     * A second call rather than fields on [month]: `spend_month` is pure over
     * records and takes no date, and these are clock-relative.
     *
     * Rust decides where the windows begin and end; this only resolves "today",
     * which is the platform's job.
     */
    fun facts(id: String, records: List<SpendRecord>, today: LocalDate = LocalDate.now()): SpendMonthFacts =
        spendMonthFacts(id, records.map { it.toFfi() }, today.toFfi())

    /**
     * The weekly series for [scope], or all spending when it is null.
     *
     * Everything about *when* a week starts is decided in Rust; the two things
     * passed in are the two the platform genuinely owns — today as a local
     * calendar date, and the locale's first weekday.
     */
    fun trend(
        scope: Category? = null,
        records: List<SpendRecord>,
        today: LocalDate = LocalDate.now(),
    ): SpendTrend = spendTrend(
        records.map { it.toFfi() },
        scope?.toFfi(),
        today.toFfi(),
        firstWeekday(),
        TREND_WEEKS,
        ROLLING_DAYS,
    )

    /**
     * This locale's first weekday, named.
     *
     * **Named, not numbered, and that is the whole point.** `spend_trend` used to
     * take a raw integer in ICU's numbering (`1 = Sunday`), which is what
     * `Calendar.firstWeekday` hands iOS directly — while Kotlin's [DayOfWeek] is
     * `MONDAY = 1`. Passing one where the other was meant is silent: nothing
     * errors, the chart still draws, and the two apps simply bucket receipts into
     * different weeks. The seam takes a [SpendWeekday] now, so the conversion
     * happens here, once, as a `when` that names every day it means.
     */
    private fun firstWeekday(): SpendWeekday =
        when (WeekFields.of(Locale.getDefault()).firstDayOfWeek) {
            DayOfWeek.MONDAY -> SpendWeekday.MONDAY
            DayOfWeek.TUESDAY -> SpendWeekday.TUESDAY
            DayOfWeek.WEDNESDAY -> SpendWeekday.WEDNESDAY
            DayOfWeek.THURSDAY -> SpendWeekday.THURSDAY
            DayOfWeek.FRIDAY -> SpendWeekday.FRIDAY
            DayOfWeek.SATURDAY -> SpendWeekday.SATURDAY
            DayOfWeek.SUNDAY -> SpendWeekday.SUNDAY
        }
}

// MARK: - Projection and re-attachment
//
// The seam's own code, and this file's real risk now that the arithmetic is
// shared. `SpendSummaryTest` covers it.

/**
 * A [SpendRecord] as the shared crate reads it. Drops `rawText`, `beancount`,
 * the photo state and the export state — none of which the arithmetic touches,
 * and the first two of which are large strings that would otherwise be copied
 * across the FFI on every render.
 */
internal fun SpendRecord.toFfi(): SpendInput = SpendInput(
    id = id,
    dateIso = result.date,
    dateIsPlaceholder = result.dateIsPlaceholder,
    scannedOn = Instant.ofEpochMilli(scannedAt).atZone(ZoneId.systemDefault()).toLocalDate().toFfi(),
    isExcluded = isExcluded,
    total = result.total,
    tax = result.tax,
    items = result.items.map { item ->
        SpendItem(
            description = item.description,
            price = item.price,
            tags = item.tags.map { it.toFfi() },
        )
    },
)

/**
 * Resolved here rather than in Rust: turning an instant into a calendar date
 * needs a timezone database *and* the offset in force at that instant, which
 * `ZoneId.systemDefault()` already has and gets right across DST.
 */
internal fun LocalDate.toFfi(): SpendDate =
    SpendDate(year = year, month = monthValue.toUInt(), day = dayOfMonth.toUInt())

internal fun ItemTag.toFfi(): SpendTag = SpendTag(path = path, display = display)

internal fun SpendSummary.Category.toFfi(): SpendCategory = when (this) {
    is SpendSummary.Category.Root -> SpendCategory.Root(id)
    is SpendSummary.Category.Leaf -> SpendCategory.Leaf(label)
}

/**
 * Put the app's own objects back on an entry Rust identified by id and index.
 *
 * Null — and so dropped — if either lookup misses. That cannot happen for a list
 * Rust derived from the very records passed in, and silently skipping beats an
 * index crash on a spending screen if it ever does.
 */
internal fun SpendItemEntry.reattach(byId: Map<String, SpendRecord>): SpendSummary.ItemEntry? {
    val record = byId[recordId] ?: return null
    val item = record.result.items.getOrNull(itemIndex.toInt()) ?: return null
    return SpendSummary.ItemEntry(id = id, item = item, record = record, amount = amount)
}
