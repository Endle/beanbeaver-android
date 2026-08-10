package com.zhenbo.beanbeaver.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.bb_mobile_ffi.SpendItemEntry
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
 * **The spend arithmetic is no longer tested here.** It moved to the shared Rust
 * crate `spend-core` (beanbeaver-mobile-util), where 28 `cargo test` cases —
 * these 20, ported one-for-one, plus 8 more — now pin it for this app *and*
 * beanbeaver-ios at once. Don't re-add month/root/leaf assertions to this file;
 * they would be a second implementation's opinion, which is the thing that was
 * just deleted.
 *
 * What is left is genuinely this side's, and is new code rather than moved code:
 * the projection from [SpendRecord] into the FFI's `SpendInput`, and the
 * re-attachment of the app's own objects onto results Rust identifies by id and
 * index. A transposition in either compiles perfectly and shows the wrong thing
 * on a screen.
 *
 * These construct FFI **records** and never call an FFI **function**: uniffi's
 * generated data classes are plain Kotlin, while a function call would load
 * `libbb_mobile_ffi.so`, which no JVM unit test has. That boundary is why the
 * arithmetic could not have stayed here even if we wanted it to.
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
        rawText: String = "",
        beancount: String = "",
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
        rawText = rawText,
        imageFilename = "receipt.jpg",
        tenders = emptyList(),
        beancount = beancount,
        beanbeaverId = null,
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

    // MARK: - Projection

    /** Every field distinct, so a swapped pair cannot pass. */
    @Test
    fun `a record projects onto the FFI input with nothing transposed`() {
        val r = record(
            id = "rec-7",
            result = result(
                date = "2026-03-02",
                total = "31.00",
                tax = "2.50",
                items = listOf(item("MILK", "10.00", listOf(tag("grocery", "Grocery")))),
            ),
            scannedAt = epochMillis(2026, 7, 20),
            isExcluded = true,
        )

        val input = r.toFfi()

        assertEquals("rec-7", input.id)
        assertEquals("2026-03-02", input.dateIso)
        assertEquals(false, input.dateIsPlaceholder)
        assertEquals(true, input.isExcluded)
        assertEquals("31.00", input.total)
        assertEquals("2.50", input.tax)
        assertEquals(2026, input.scannedOn.year)
        assertEquals(7u, input.scannedOn.month)
        assertEquals(20u, input.scannedOn.day)
        assertEquals(1, input.items.size)
        assertEquals("MILK", input.items[0].description)
        assertEquals("10.00", input.items[0].price)
        assertEquals("grocery", input.items[0].tags[0].path)
        assertEquals("Grocery", input.items[0].tags[0].display)
    }

    /**
     * The flag Rust cannot infer. Getting it backwards would silently rebucket
     * every receipt whose date the parser only guessed.
     */
    @Test
    fun `the placeholder flag is carried, not folded into a null date`() {
        val r = record(result = result(date = "2026-03-02", dateIsPlaceholder = true))
        val input = r.toFfi()
        // Still carries the date — Rust decides what to do with it, not this side.
        assertEquals("2026-03-02", input.dateIso)
        assertEquals(true, input.dateIsPlaceholder)
    }

    /**
     * `rawText` and `beancount` are the reason the projection exists: both apps
     * recompute the summary on every render, so passing whole parse results
     * would copy every OCR dump per frame.
     */
    @Test
    fun `the projection drops the large strings the arithmetic never reads`() {
        val r = record(result = result(rawText = "x".repeat(50_000), beancount = "y".repeat(50_000)))
        // Nothing on SpendInput can carry them; this is a compile-time guarantee
        // restated as a test so removing a field is a deliberate act.
        val fields = uniffi.bb_mobile_ffi.SpendInput::class.java.declaredFields.map { it.name }
        assertEquals(emptyList<String>(), fields.filter { it == "rawText" || it == "beancount" })
        assertEquals(0, r.toFfi().items.size)
    }

    /**
     * Resolved on this side because it needs a timezone database *and* the offset
     * in force at that instant — which is why Rust takes a calendar date, not
     * epoch millis.
     */
    @Test
    fun `a scan time resolves to its local calendar date`() {
        val r = record(result = result(), scannedAt = epochMillis(2026, 5, 4))
        val d = r.toFfi().scannedOn
        assertEquals(2026, d.year)
        assertEquals(5u, d.month)
        assertEquals(4u, d.day)
    }

    @Test
    fun `a category projects onto the FFI enum`() {
        val root = SpendSummary.Category.Root("grocery").toFfi()
        assertEquals(uniffi.bb_mobile_ffi.SpendCategory.Root("grocery"), root)
        val leaf = SpendSummary.Category.Leaf("Dairy").toFfi()
        assertEquals(uniffi.bb_mobile_ffi.SpendCategory.Leaf("Dairy"), leaf)
    }

    // MARK: - Re-attachment

    /**
     * Rust returns an index into the receipt, not the item. Picking the wrong one
     * puts a real price next to the wrong description — the kind of thing that
     * looks like a parser bug from a screenshot.
     */
    @Test
    fun `an entry re-attaches the item at its own index`() {
        val rec = record(
            id = "rec-a",
            result = result(
                items = listOf(
                    item("MILK", "10.00"),
                    item("BREAD", "1.00"),
                    item("YOGURT", "4.00", quantity = 3),
                ),
            ),
        )
        val byId = mapOf(rec.id to rec)

        val entry = SpendItemEntry(
            id = "rec-a-2",
            recordId = "rec-a",
            itemIndex = 2u,
            description = "YOGURT",
            price = "4.00",
            amount = 4.0,
        ).reattach(byId)

        assertEquals("rec-a-2", entry!!.id)
        assertEquals(rec, entry.record)
        assertEquals("YOGURT", entry.item.description)
        // Proof it is the app's own object and not one rebuilt from the FFI
        // payload: quantity never crosses the seam.
        assertEquals(3, entry.item.quantity)
        assertEquals(4.0, entry.amount, 0.001)
    }

    /**
     * Cannot happen for a list Rust derived from the records passed in — but
     * skipping beats an index crash on a spending screen if it ever does.
     */
    @Test
    fun `an entry pointing at nothing is dropped rather than crashing`() {
        val rec = record(id = "rec-a", result = result(items = listOf(item("MILK", "10.00"))))
        val byId = mapOf(rec.id to rec)

        val unknownRecord = SpendItemEntry("x", "rec-zzz", 0u, "MILK", "10.00", 10.0)
        assertNull(unknownRecord.reattach(byId))

        val indexPastEnd = SpendItemEntry("x", "rec-a", 9u, "MILK", "10.00", 10.0)
        assertNull(indexPastEnd.reattach(byId))
    }

    @Test
    fun `a local date projects onto the FFI date`() {
        val d = LocalDate.of(2020, 1, 9).toFfi()
        assertEquals(2020, d.year)
        assertEquals(1u, d.month)
        assertEquals(9u, d.day)
    }
}
