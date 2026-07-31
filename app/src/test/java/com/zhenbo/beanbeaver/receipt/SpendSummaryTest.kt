package com.zhenbo.beanbeaver.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.bb_receipt_ffi.FieldConfidences
import uniffi.bb_receipt_ffi.ItemTag
import uniffi.bb_receipt_ffi.MerchantMatch
import uniffi.bb_receipt_ffi.MerchantMatchStatus
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.ScanTimings
import java.time.LocalDate
import java.time.ZoneId

/**
 * [SpendSummary] is the one part of spend tracking that is pure arithmetic — no
 * Android, no preferences, no views — which is exactly why it can be pinned here
 * rather than eyeballed on a screen. These assert the *behaviour* the iOS twin
 * documents, not the Kotlin implementation, so a divergence between the two apps
 * shows up as a failing test.
 */
class SpendSummaryTest {

    // MARK: - Fixtures

    private fun tag(path: String, display: String) = ItemTag(path, display)

    private fun item(
        description: String,
        price: String,
        tags: List<ItemTag> = emptyList(),
        quantity: Int = 1,
        account: String? = null,
    ) = ReceiptItem(description, price, quantity, account, tags)

    private fun result(
        merchant: String = "COSTCO",
        date: String? = "2026-07-15",
        dateIsPlaceholder: Boolean = false,
        total: String = "0.00",
        tax: String? = null,
        items: List<ReceiptItem> = emptyList(),
        beanbeaverId: String? = null,
    ) = ReceiptResult(
        merchant = merchant,
        merchantMatch = MerchantMatch(merchant, merchant, MerchantMatchStatus.UNKNOWN, 0.0),
        date = date,
        dateIsPlaceholder = dateIsPlaceholder,
        total = total,
        tax = tax,
        subtotal = null,
        items = items,
        warnings = emptyList(),
        warningAfterItemIndices = emptyList(),
        rawText = "",
        imageFilename = "receipt.jpg",
        tenders = emptyList(),
        beancount = "",
        beanbeaverId = beanbeaverId,
        documentRelpath = null,
        timings = ScanTimings(emptyList()),
        confidence = FieldConfidences(0.0, 0.0, 0.0, 0.0, false),
        detections = emptyList(),
    )

    private fun record(
        id: String = "r1",
        result: ReceiptResult,
        scannedAt: Long = epochMillis(2026, 7, 20),
        isExcluded: Boolean = false,
    ) = SpendRecord(
        id = id,
        result = result,
        scannedAt = scannedAt,
        captureFilename = "$id.jpg",
        isExcluded = isExcluded,
    )

    private fun epochMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val grocery = tag("grocery", "Grocery")
    private val dairy = tag("grocery/dairy", "Dairy")
    private val household = tag("household", "Household")
    private val supply = tag("household/supply", "Supply")

    // MARK: - Month bucketing

    /** The receipt's own date wins — that's the day the money was spent. */
    @Test
    fun `a record buckets by its receipt date`() {
        val r = record(result = result(date = "2026-03-02"), scannedAt = epochMillis(2026, 7, 20))
        assertEquals("2026-03", SpendSummary.monthId(r))
    }

    /**
     * A placeholder date falls back to the row's *own* scan time, not "today", so
     * a bucket can't drift with the clock on a later run.
     */
    @Test
    fun `a placeholder date falls back to the scan time, not today`() {
        val r = record(
            result = result(date = "2026-03-02", dateIsPlaceholder = true),
            scannedAt = epochMillis(2026, 7, 20),
        )
        assertEquals("2026-07", SpendSummary.monthId(r))
    }

    @Test
    fun `a missing date falls back to the scan time`() {
        val r = record(result = result(date = null), scannedAt = epochMillis(2026, 5, 4))
        assertEquals("2026-05", SpendSummary.monthId(r))
    }

    @Test
    fun `months are listed newest first`() {
        val records = listOf(
            record(id = "a", result = result(date = "2026-05-01")),
            record(id = "b", result = result(date = "2026-07-01")),
            record(id = "c", result = result(date = "2026-06-01")),
            record(id = "d", result = result(date = "2026-07-20")),
        )
        assertEquals(listOf("2026-07", "2026-06", "2026-05"), SpendSummary.monthIds(records))
    }

    /**
     * Deliberately *not* "the current month": a screen opening on a $0.00 month
     * because the last receipt was in September shows nothing and looks broken.
     */
    @Test
    fun `the default month is the newest with receipts, not the current one`() {
        val records = listOf(record(result = result(date = "2020-01-15")))
        assertEquals("2020-01", SpendSummary.defaultMonthId(records))
    }

    @Test
    fun `with nothing scanned the default month is the current one`() {
        assertEquals(SpendSummary.currentMonthId(), SpendSummary.defaultMonthId(emptyList()))
    }

    // MARK: - Arithmetic

    @Test
    fun `tracked is items plus tax, and roots sum to items`() {
        val records = listOf(
            record(
                result = result(
                    total = "27.00",
                    tax = "2.00",
                    items = listOf(
                        item("MILK", "10.00", listOf(grocery, dairy)),
                        item("PAPER TOWELS", "15.00", listOf(household, supply)),
                    ),
                ),
            ),
        )
        val month = SpendSummary.month("2026-07", records)
        assertEquals(25.0, month.itemsTotal, 0.001)
        assertEquals(2.0, month.tax, 0.001)
        assertEquals(27.0, month.tracked, 0.001)
        assertEquals(25.0, month.roots.sumOf { it.amount }, 0.001)
        // Items + tax landed exactly on the receipt total, so there is no gap.
        assertNull(month.unaccounted)
    }

    /**
     * The reconciliation row exists for exactly this: a scan that read every item
     * but missed a discount line. It's named rather than hidden, because otherwise
     * it looks like arithmetic the app got wrong.
     */
    @Test
    fun `a gap against the receipt total is reported as unaccounted`() {
        val records = listOf(
            record(
                result = result(
                    total = "20.00",
                    items = listOf(item("MILK", "25.00", listOf(grocery, dairy))),
                ),
            ),
        )
        val month = SpendSummary.month("2026-07", records)
        assertEquals(5.0, month.unaccounted!!, 0.001)
    }

    @Test
    fun `roots and leaves are ordered largest first`() {
        val records = listOf(
            record(
                result = result(
                    items = listOf(
                        item("MILK", "5.00", listOf(grocery, dairy)),
                        item("YOGURT", "9.00", listOf(grocery, dairy)),
                        item("BREAD", "3.00", listOf(grocery, tag("grocery/bakery", "Bakery"))),
                        item("PAPER TOWELS", "40.00", listOf(household, supply)),
                    ),
                ),
            ),
        )
        val month = SpendSummary.month("2026-07", records)
        assertEquals(listOf("household", "grocery"), month.roots.map { it.id })
        val groceryLeaves = month.group("grocery")!!.leaves
        assertEquals(listOf("Dairy", "Bakery"), groceryLeaves.map { it.label })
        assertEquals(14.0, groceryLeaves.first().amount, 0.001)
        assertEquals(2, groceryLeaves.first().itemCount)
    }

    /**
     * The vocabulary's own wording beats capitalizing a raw path segment — the
     * reason `personalcare` reads as "Personal Care" and not "Personalcare".
     */
    @Test
    fun `a root takes its authored label when an item carries the root tag`() {
        val records = listOf(
            record(
                result = result(
                    items = listOf(
                        item("SHAMPOO", "8.00", listOf(tag("personalcare", "Personal Care"))),
                    ),
                ),
            ),
        )
        assertEquals("Personal Care", SpendSummary.month("2026-07", records).roots.single().label)
    }

    /** Untagged items stay visible, so the breakdown reconciles against what was scanned. */
    @Test
    fun `untagged items land in a real Uncategorized group`() {
        val records = listOf(
            record(result = result(items = listOf(item("MYSTERY", "3.00")))),
        )
        val month = SpendSummary.month("2026-07", records)
        assertEquals(SpendSummary.UNCATEGORIZED_ROOT, month.roots.single().id)
        assertEquals("Uncategorized", month.roots.single().label)
        assertEquals(3.0, month.itemsTotal, 0.001)
    }

    /**
     * An unreadable price is counted and carried at zero rather than dropped: the
     * item still happened, and the footer says how many couldn't be read.
     */
    @Test
    fun `an unreadable price is counted, not silently treated as free`() {
        val records = listOf(
            record(
                result = result(
                    items = listOf(
                        item("MILK", "10.00", listOf(grocery, dairy)),
                        item("SMUDGED", "N/A", listOf(grocery, dairy)),
                    ),
                ),
            ),
        )
        val month = SpendSummary.month("2026-07", records)
        assertEquals(1, month.unreadablePriceCount)
        assertEquals(10.0, month.itemsTotal, 0.001)
        assertEquals(2, month.group("grocery")!!.itemCount)
    }

    @Test
    fun `an excluded receipt is counted but contributes nothing`() {
        val records = listOf(
            record(id = "a", result = result(total = "10.00", items = listOf(item("MILK", "10.00", listOf(grocery, dairy))))),
            record(id = "b", result = result(total = "99.00", items = listOf(item("WINE", "99.00", listOf(grocery)))), isExcluded = true),
        )
        val month = SpendSummary.month("2026-07", records)
        assertEquals(10.0, month.itemsTotal, 0.001)
        assertEquals(1, month.excludedCount)
        assertEquals(1, month.receiptCount)
        // `records` keeps the excluded row: the Receipts list still shows it.
        assertEquals(2, month.records.size)
    }

    /** One scale for every bar on the screen, so two cards are actually comparable. */
    @Test
    fun `maxLeafAmount spans every root`() {
        val records = listOf(
            record(
                result = result(
                    items = listOf(
                        item("MILK", "5.00", listOf(grocery, dairy)),
                        item("PAPER TOWELS", "40.00", listOf(household, supply)),
                    ),
                ),
            ),
        )
        assertEquals(40.0, SpendSummary.month("2026-07", records).maxLeafAmount, 0.001)
    }

    // MARK: - Drill-down

    /**
     * A root matches on its **raw tag id**. Matching on the display label would
     * drop every item in the group that didn't itself carry the root tag.
     */
    @Test
    fun `a root drill-down catches items that never carried the root tag`() {
        val records = listOf(
            record(
                result = result(
                    items = listOf(
                        item("MILK", "10.00", listOf(grocery, dairy)),
                        // Only the leaf tag — no bare "grocery" on this line.
                        item("BREAD", "4.00", listOf(tag("grocery/bakery", "Bakery"))),
                    ),
                ),
            ),
        )
        val entries = SpendSummary.items(SpendSummary.Category.Root("grocery"), records)
        assertEquals(listOf("MILK", "BREAD"), entries.map { it.item.description })
        assertEquals(14.0, entries.sumOf { it.amount }, 0.001)
    }

    @Test
    fun `a leaf drill-down matches the label the total was accumulated under`() {
        val records = listOf(
            record(
                result = result(
                    items = listOf(
                        item("MILK", "10.00", listOf(grocery, dairy)),
                        item("BREAD", "4.00", listOf(grocery, tag("grocery/bakery", "Bakery"))),
                    ),
                ),
            ),
        )
        val entries = SpendSummary.items(SpendSummary.Category.Leaf("Dairy"), records)
        assertEquals(listOf("MILK"), entries.map { it.item.description })
    }

    /**
     * The grouping is folded from the flat list, so a receipt's share can never
     * disagree with the category total that was tapped to reach it.
     */
    @Test
    fun `receipt groups sum to the category total`() {
        val records = listOf(
            record(id = "a", result = result(total = "14.00", items = listOf(
                item("MILK", "10.00", listOf(grocery, dairy)),
                item("YOGURT", "4.00", listOf(grocery, dairy)),
            ))),
            record(id = "b", result = result(total = "6.00", items = listOf(
                item("CHEESE", "6.00", listOf(grocery, dairy)),
            ))),
        )
        val category = SpendSummary.Category.Leaf("Dairy")
        val groups = SpendSummary.receipts(category, records)
        assertEquals(2, groups.size)
        assertEquals(listOf(2, 1), groups.map { it.entries.size })
        assertEquals(14.0, groups.first().amount, 0.001)
        assertEquals(
            SpendSummary.items(category, records).sumOf { it.amount },
            groups.sumOf { it.amount },
            0.001,
        )
        // The receipt's own total is context only — never what the group spends.
        assertEquals(14.0, groups.first().receiptTotal!!, 0.001)
    }

    @Test
    fun `an excluded receipt is absent from the drill-down too`() {
        val records = listOf(
            record(id = "a", result = result(items = listOf(item("MILK", "10.00", listOf(grocery, dairy))))),
            record(id = "b", result = result(items = listOf(item("CHEESE", "6.00", listOf(grocery, dairy)))), isExcluded = true),
        )
        val entries = SpendSummary.items(SpendSummary.Category.Leaf("Dairy"), records)
        assertEquals(listOf("MILK"), entries.map { it.item.description })
    }

    /** Two identical lines on one receipt have to stay distinct rows. */
    @Test
    fun `duplicate lines on one receipt get distinct entry ids`() {
        val records = listOf(
            record(result = result(items = listOf(
                item("MILK", "6.69", listOf(grocery, dairy)),
                item("MILK", "6.69", listOf(grocery, dairy)),
            ))),
        )
        val ids = SpendSummary.items(SpendSummary.Category.Leaf("Dairy"), records).map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `month labels read the way a person writes them`() {
        assertTrue(SpendSummary.monthLabel("2026-07").contains("2026"))
        // Unparseable input falls back to the raw id rather than vanishing.
        assertEquals("not-a-month", SpendSummary.monthLabel("not-a-month"))
    }
}
