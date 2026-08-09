package com.zhenbo.beanbeaver.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.bb_receipt_ffi.ReceiptWarning
import uniffi.bb_receipt_ffi.ReceiptWarningKind

/**
 * The ranking is a product decision, not an implementation detail — the whole
 * point of core v0.8.0's typed kinds was to stop the phone guessing what a
 * finding is worth. These pin the ranking that decision produced, in the same
 * shape as iOS `WarningSeverity.swift`, so a reshuffle has to be deliberate.
 */
class WarningSeverityTest {

    private fun warning(kind: ReceiptWarningKind) =
        ReceiptWarning(kind = kind, message = "m", afterItemIndex = -1)

    @Test
    fun `only a broken sum is loud enough to badge`() {
        assertEquals(WarningSeverity.ATTENTION, ReceiptWarningKind.TOTAL_MISMATCH.severity)
        assertEquals(WarningSeverity.ATTENTION, ReceiptWarningKind.SUBTOTAL_MISMATCH.severity)
    }

    @Test
    fun `a lost line or a disputed tender is worth reading, not flagging`() {
        assertEquals(WarningSeverity.NOTICE, ReceiptWarningKind.POSSIBLE_MISSED_ITEM.severity)
        assertEquals(WarningSeverity.NOTICE, ReceiptWarningKind.DROPPED_IMPLAUSIBLE_PRICE.severity)
        assertEquals(WarningSeverity.NOTICE, ReceiptWarningKind.TENDER_MISMATCH.severity)
    }

    /**
     * The regression this whole file exists for: an unclassified line is normal
     * (a correctly parsed discount matches no product rule), and as an untyped
     * warning it badged 83 of 124 corpus receipts.
     */
    @Test
    fun `an uncategorized item never reaches the card`() {
        assertEquals(WarningSeverity.INFO, ReceiptWarningKind.UNCATEGORIZED_ITEM.severity)
        assertEquals(WarningSeverity.INFO, ReceiptWarningKind.PRICE_AUTO_CORRECTED.severity)
        assertTrue(
            listOf(
                warning(ReceiptWarningKind.UNCATEGORIZED_ITEM),
                warning(ReceiptWarningKind.PRICE_AUTO_CORRECTED),
            ).worthShowing.isEmpty(),
        )
    }

    @Test
    fun `the banner takes the loudest finding`() {
        val mixed = listOf(
            warning(ReceiptWarningKind.UNCATEGORIZED_ITEM),
            warning(ReceiptWarningKind.POSSIBLE_MISSED_ITEM),
            warning(ReceiptWarningKind.TOTAL_MISMATCH),
        )
        assertEquals(WarningSeverity.ATTENTION, mixed.highestSeverity)
        assertEquals(2, mixed.worthShowing.size)
        assertNull(emptyList<ReceiptWarning>().highestSeverity)
    }

    @Test
    fun `every kind is ranked, and info never counts as showable`() {
        // Fails the day core adds a variant nobody ranked: the `else ->` arm
        // keeps it from crashing, this keeps it from going unnoticed.
        ReceiptWarningKind.entries.forEach { kind ->
            val shown = listOf(warning(kind)).worthShowing.isNotEmpty()
            assertEquals(
                "$kind: worthShowing must agree with its severity",
                kind.severity >= WarningSeverity.NOTICE,
                shown,
            )
        }
        assertFalse(WarningSeverity.INFO >= WarningSeverity.NOTICE)
    }
}
