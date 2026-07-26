package com.zhenbo.beanbeaver.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free `.xlsx` (Office Open XML SpreadsheetML) writer —
 * Kotlin twin of iOS `MoneyManagerWorkbook`.
 *
 * Realbyte's *Money Manager* imports transactions from an Excel file
 * (More → Backup → Import excel file), so [MoneyManagerExport] has to hand it a
 * genuine `.xlsx`. We emit the handful of XML parts a spreadsheet needs and pack
 * them with `java.util.zip` — where iOS hand-rolls a ZIP writer because the
 * platform vends no public zip API, the JVM already has one, so this side stays
 * much shorter.
 *
 * Every cell is written as an **inline string** (`t="inlineStr"`); Money Manager
 * reads the cell's text regardless — exactly as it does the tab-separated files
 * the community migration tools feed the same importer — so we skip a
 * shared-strings table, a styles table, and number formatting entirely.
 *
 * Kept independent of the scan types (it takes `List<List<String>>`, not
 * receipts) so a plain JVM unit test can exercise it without an emulator.
 */
object MoneyManagerWorkbook {

    /**
     * Build a one-worksheet `.xlsx` from [rows] — each an array of cell strings,
     * the first row treated like any other (the caller supplies the header).
     * Returns the finished archive bytes.
     */
    fun xlsx(sheetName: String, rows: List<List<String>>): ByteArray {
        val parts = listOf(
            "[Content_Types].xml" to CONTENT_TYPES_XML,
            "_rels/.rels" to ROOT_RELS_XML,
            "xl/workbook.xml" to workbookXml(sheetName),
            "xl/_rels/workbook.xml.rels" to WORKBOOK_RELS_XML,
            "xl/worksheets/sheet1.xml" to sheetXml(rows),
        )

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (path, xml) ->
                // A constant timestamp keeps the output byte-for-byte deterministic:
                // the importer doesn't care, and a test can assert on whole bytes.
                val entry = ZipEntry(path).apply { time = FIXED_ENTRY_TIME }
                zip.putNextEntry(entry)
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // MARK: - OOXML parts

    private const val CONTENT_TYPES_XML =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """</Types>"""

    private const val ROOT_RELS_XML =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private const val WORKBOOK_RELS_XML =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """</Relationships>"""

    private fun workbookXml(sheetName: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${escapeAttr(sheetName)}" sheetId="1" r:id="rId1"/></sheets>""" +
            """</workbook>"""

    private fun sheetXml(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, row ->
            val rowNumber = r + 1
            sb.append("""<row r="$rowNumber">""")
            row.forEachIndexed { c, cell ->
                val ref = "${columnName(c)}$rowNumber"
                sb.append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">""")
                sb.append(escapeText(cell))
                sb.append("""</t></is></c>""")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // MARK: - Helpers

    /** Zero-based column index to its spreadsheet label: 0→"A", 25→"Z", 26→"AA". */
    internal fun columnName(index: Int): String {
        var i = index
        var name = ""
        do {
            name = ('A' + i % 26) + name
            i = i / 26 - 1
        } while (i >= 0)
        return name
    }

    private fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;")

    /** 1980-01-01 00:00:00 UTC — the ZIP epoch, and a fixed value for reproducibility. */
    private const val FIXED_ENTRY_TIME = 315532800000L
}
