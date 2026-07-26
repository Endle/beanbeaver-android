package com.zhenbo.beanbeaver.export

import android.content.Context
import com.zhenbo.beanbeaver.ui.tagDisplay
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Turns scanned receipts into an `.xlsx` that Realbyte's *Money Manager* app
 * imports (More → Backup → Import excel file). One row per line item, so the
 * per-item categorization BeanBeaver produces survives the trip — the whole
 * reason to itemize in the first place. Kotlin twin of iOS `MoneyManagerExport`.
 *
 * Realbyte's fixed column order is
 * `Date, Account, Category, Subcategory, Note, Amount, Income/Expense, Description`,
 * dates `MM/dd/yyyy`, and both `Account` and `Category` must be non-empty
 * (ideally matching names already set up in the user's Money Manager). See
 * help.realbyteapps.com/hc/en-us/articles/360043223253. The container itself is
 * built by [MoneyManagerWorkbook].
 */
object MoneyManagerExport {
    /** SharedPreferences key the settings screen writes and this reads, so the
     *  exported `Account` column matches an account in the user's Money Manager. */
    const val ACCOUNT_KEY = "moneyManagerAccount"
    const val DEFAULT_ACCOUNT = "Cash"

    private const val PREFS = "beanbeaver"

    val HEADER = listOf(
        "Date", "Account", "Category", "Subcategory",
        "Note", "Amount", "Income/Expense", "Description",
    )

    /** The configured account name, or [DEFAULT_ACCOUNT] when unset/blank —
     *  never empty, since Realbyte rejects a blank `Account`. */
    fun account(context: Context): String {
        val raw = prefs(context).getString(ACCOUNT_KEY, null)?.trim()
        return if (raw.isNullOrEmpty()) DEFAULT_ACCOUNT else raw
    }

    fun setAccount(context: Context, value: String) {
        prefs(context).edit().putString(ACCOUNT_KEY, value.trim()).apply()
    }

    /**
     * Header row followed by one row per line item across every result. A receipt
     * with no parsed items still contributes a single row for its total, so
     * nothing scanned is silently dropped from the export.
     */
    fun rows(results: List<ReceiptResult>, account: String, today: Date = Date()): List<List<String>> {
        val out = mutableListOf(HEADER)
        results.forEach { result ->
            val date = dateString(result, today)
            if (result.items.isEmpty()) {
                out.add(
                    row(
                        category = "Uncategorized", note = result.merchant,
                        price = result.total, merchant = result.merchant,
                        date = date, account = account,
                    ),
                )
            } else {
                result.items.forEach { item ->
                    val note =
                        if (item.quantity > 1) "${item.description} ×${item.quantity}" else item.description
                    out.add(
                        row(
                            category = category(item), note = note,
                            price = item.price, merchant = result.merchant,
                            date = date, account = account,
                        ),
                    )
                }
            }
        }
        return out
    }

    /**
     * Serialize [results] to an `.xlsx` in the app's cache and return the file,
     * ready to hand to a share intent through the FileProvider.
     */
    fun makeFile(context: Context, results: List<ReceiptResult>, today: Date = Date()): File {
        val bytes = MoneyManagerWorkbook.xlsx(
            sheetName = "Transactions",
            rows = rows(results, account(context), today),
        )
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(today))
        file.writeBytes(bytes)
        return file
    }

    // MARK: - Row mapping

    private fun row(
        category: String, note: String, price: String,
        merchant: String, date: String, account: String,
    ): List<String> {
        val amount = amountString(price)
        return listOf(
            date,
            account,
            category,
            "", // Subcategory — unused in v1
            note,
            amount.magnitude,
            if (amount.isNegative) "Income" else "Expense", // a negative line is a discount/refund
            merchant,
        )
    }

    /** The most-specific classifier tag, capitalized — the same label the result
     *  screen shows (`tagDisplay`). Never empty. */
    private fun category(item: ReceiptItem): String =
        tagDisplay(item.tags).primary ?: "Uncategorized"

    /**
     * `result.date` (ISO `yyyy-MM-dd`) as `MM/dd/yyyy`. A missing or placeholder
     * date falls back to today — Realbyte requires a real date, and the scan date
     * is the best stand-in.
     */
    private fun dateString(result: ReceiptResult, today: Date): String {
        val iso = result.date
        if (!result.dateIsPlaceholder && iso != null) {
            val parsed = runCatching { isoParser().parse(iso) }.getOrNull()
            if (parsed != null) return usFormatter().format(parsed)
        }
        return usFormatter().format(today)
    }

    /** Magnitude of a loosely-formatted price as a plain 2-dp number string, plus
     *  its sign — mirrors `formatPrice` but emits a bare number (Money Manager
     *  wants the amount with no currency symbol). Unparseable → "0.00" positive,
     *  so a row never carries a blank amount. */
    internal data class Amount(val magnitude: String, val isNegative: Boolean)

    internal fun amountString(raw: String): Amount {
        val filtered = raw.filter { it.isDigit() || it == '.' || it == '-' }
        val value = filtered.toDoubleOrNull() ?: return Amount("0.00", false)
        return Amount("%.2f".format(abs(value)), value < 0)
    }

    private fun fileName(today: Date): String =
        "beanbeaver-moneymanager-${fileStampFormatter().format(today)}.xlsx"

    // SimpleDateFormat isn't thread-safe, so build one per call rather than
    // sharing a mutable static the way the Swift side's immutable formatters can.
    private fun isoParser() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun usFormatter() = SimpleDateFormat("MM/dd/yyyy", Locale.US)
    private fun fileStampFormatter() = SimpleDateFormat("yyyyMMdd", Locale.US)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
