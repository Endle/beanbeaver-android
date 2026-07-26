package com.zhenbo.beanbeaver.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The `.xlsx` writer is the one piece of this app that has to satisfy a foreign
 * parser (Realbyte's importer), so it's worth pinning down: the archive has to
 * hold the five OOXML parts, and the sheet has to carry the cells we handed it.
 */
class MoneyManagerWorkbookTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `archive holds the five parts a spreadsheet needs`() {
        val bytes = MoneyManagerWorkbook.xlsx("Transactions", listOf(listOf("A")))
        val names = entries(bytes).keys
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
            ),
            names,
        )
    }

    @Test
    fun `cells land at the right references`() {
        val bytes = MoneyManagerWorkbook.xlsx(
            "Transactions",
            listOf(listOf("Date", "Account"), listOf("01/02/2026", "Cash")),
        )
        val sheet = entries(bytes)["xl/worksheets/sheet1.xml"]
        assertNotNull(sheet)
        // Header on row 1, data on row 2, columns A and B.
        assertTrue(sheet!!.contains("""<c r="A1" t="inlineStr"><is><t xml:space="preserve">Date</t>"""))
        assertTrue(sheet.contains("""<c r="B1" t="inlineStr"><is><t xml:space="preserve">Account</t>"""))
        assertTrue(sheet.contains("""<c r="A2" t="inlineStr"><is><t xml:space="preserve">01/02/2026</t>"""))
        assertTrue(sheet.contains("""<c r="B2" t="inlineStr"><is><t xml:space="preserve">Cash</t>"""))
    }

    /** A merchant like "M&S" would otherwise produce XML that no parser accepts. */
    @Test
    fun `cell text is XML-escaped`() {
        val bytes = MoneyManagerWorkbook.xlsx("S", listOf(listOf("M&S <deli>")))
        val sheet = entries(bytes)["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("M&amp;S &lt;deli&gt;"))
        assertTrue("raw ampersand leaked into the sheet", !sheet.contains("M&S"))
    }

    @Test
    fun `sheet name is attribute-escaped`() {
        val bytes = MoneyManagerWorkbook.xlsx("""a"b""", listOf(listOf("x")))
        assertTrue(entries(bytes)["xl/workbook.xml"]!!.contains("""name="a&quot;b""""))
    }

    @Test
    fun `column names roll over past Z`() {
        assertEquals("A", MoneyManagerWorkbook.columnName(0))
        assertEquals("H", MoneyManagerWorkbook.columnName(7)) // the last Realbyte column
        assertEquals("Z", MoneyManagerWorkbook.columnName(25))
        assertEquals("AA", MoneyManagerWorkbook.columnName(26))
        assertEquals("AB", MoneyManagerWorkbook.columnName(27))
        assertEquals("BA", MoneyManagerWorkbook.columnName(52))
    }

    /** Fixed entry timestamps — two runs of the same data must be byte-identical. */
    @Test
    fun `output is deterministic`() {
        val rows = listOf(listOf("Date", "Account"), listOf("01/02/2026", "Cash"))
        assertTrue(
            MoneyManagerWorkbook.xlsx("Transactions", rows)
                .contentEquals(MoneyManagerWorkbook.xlsx("Transactions", rows)),
        )
    }
}
