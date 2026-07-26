package com.zhenbo.beanbeaver.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prices arrive from OCR loosely formatted, and Realbyte wants a bare number plus
 * a separate Income/Expense column — so the sign has to come out of the amount and
 * become a *kind*. A row must never carry a blank amount.
 */
class MoneyManagerAmountTest {

    @Test
    fun `plain prices normalize to two decimals`() {
        assertEquals(MoneyManagerExport.Amount("17.19", false), MoneyManagerExport.amountString("17.1900"))
        assertEquals(MoneyManagerExport.Amount("2.49", false), MoneyManagerExport.amountString("$2.49"))
        assertEquals(MoneyManagerExport.Amount("8.00", false), MoneyManagerExport.amountString("8"))
    }

    /** A negative line is a discount/refund — magnitude in Amount, sign in the kind. */
    @Test
    fun `negative prices report their sign separately`() {
        assertEquals(MoneyManagerExport.Amount("3.50", true), MoneyManagerExport.amountString("-3.5000"))
        assertEquals(MoneyManagerExport.Amount("1.25", true), MoneyManagerExport.amountString("-$1.25"))
    }

    @Test
    fun `unparseable prices fall back to zero rather than blank`() {
        assertEquals(MoneyManagerExport.Amount("0.00", false), MoneyManagerExport.amountString(""))
        assertEquals(MoneyManagerExport.Amount("0.00", false), MoneyManagerExport.amountString("N/A"))
    }
}
