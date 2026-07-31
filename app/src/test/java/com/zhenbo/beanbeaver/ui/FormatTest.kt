package com.zhenbo.beanbeaver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.bb_receipt_ffi.ItemTag

/**
 * The display helpers are the iOS twins in `Theme.swift` — the two apps are
 * supposed to render the same receipt identically, so these pin the shared
 * behaviour rather than the Kotlin implementation.
 */
class FormatTest {

    @Test
    fun `prices normalize to a consistent currency string`() {
        assertEquals(PriceDisplay("$17.19", false), formatPrice("17.1900"))
        assertEquals(PriceDisplay("-$3.50", true), formatPrice("-3.5000"))
        assertEquals(PriceDisplay("$2.49", false), formatPrice("$2.49"))
    }

    /** Anything unparseable is shown unchanged — nothing is silently hidden. */
    @Test
    fun `unparseable prices pass through untouched`() {
        assertEquals(PriceDisplay("N/A", false), formatPrice("N/A"))
    }

    @Test
    fun `tags lead with the most specific`() {
        val display = tagDisplay(
            listOf(
                ItemTag("grocery", "Grocery"),
                ItemTag("grocery/meat", "Meat"),
                ItemTag("grocery/meat/chicken", "Chicken"),
            ),
        )
        assertEquals("Chicken", display.primary)
        assertEquals(listOf("Grocery", "Meat"), display.rest)
    }

    /**
     * The whole point of core v0.7.0's authored `display`: the label is taken
     * verbatim rather than capitalized from the path, which used to render
     * `energy_drink` as "Energy_drink".
     */
    @Test
    fun `labels come from the vocabulary, not from the path`() {
        val display = tagDisplay(
            listOf(
                ItemTag("grocery", "Grocery"),
                ItemTag("grocery/energy_drink", "Energy Drink"),
            ),
        )
        assertEquals("Energy Drink", display.primary)
    }

    @Test
    fun `empty tags have no primary`() {
        assertNull(tagDisplay(emptyList()).primary)
        assertNull(tagDisplay(listOf(ItemTag("grocery", ""))).primary)
    }

    @Test
    fun `dates render the way a person writes them`() {
        assertEquals("Mar 1, 2026", friendlyDate("2026-03-01"))
        assertNull(friendlyDate(null))
        // Unparseable input falls back to the raw string rather than vanishing.
        assertEquals("not-a-date", friendlyDate("not-a-date"))
    }

    @Test
    fun `merchant names are title-cased`() {
        assertEquals("Costco Wholesale", titleCase("COSTCO WHOLESALE"))
    }

    @Test
    fun `byte counts read the way a storage row should`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 kB", formatBytes(1000))
        assertEquals("1.5 MB", formatBytes(1_500_000))
        assertEquals("2.0 GB", formatBytes(2_000_000_000))
    }
}
