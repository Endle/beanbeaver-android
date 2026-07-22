package com.beanbeaver.app.receipt

import android.content.Context
import java.util.Currency
import java.util.Locale

/**
 * Per-device ledger-output settings that shape every generated beancount entry:
 * the operating currency (the commodity on each amount) and the account the tax
 * posting lands on. Kotlin twin of iOS `LedgerFormatPrefs`. Read at scan time
 * (see [ReceiptPipeline.scan]) so a change in Settings takes effect on the next
 * scan; edited by the Settings screen's pickers.
 */
object LedgerFormatPrefs {
    const val CURRENCY_KEY = "ledgerCurrency"
    const val TAX_ACCOUNT_KEY = "ledgerTaxAccount"

    /** Fallbacks matching the app's historical Canadian defaults. */
    const val DEFAULT_CURRENCY = "CAD"
    const val DEFAULT_TAX_ACCOUNT = "Expenses:Tax:HST"

    private const val PREFS = "beanbeaver"

    /** The device locale's ISO 4217 currency, if it exposes one. */
    fun localeCurrency(): String? =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()

    /**
     * Effective operating currency: the user's stored choice, else the device
     * locale's currency, else [DEFAULT_CURRENCY].
     */
    fun currency(context: Context): String {
        val stored = prefs(context).getString(CURRENCY_KEY, null)
        if (!stored.isNullOrEmpty()) return stored
        return localeCurrency() ?: DEFAULT_CURRENCY
    }

    /** Effective tax account: the user's stored choice, else [DEFAULT_TAX_ACCOUNT]. */
    fun taxAccount(context: Context): String {
        val stored = prefs(context).getString(TAX_ACCOUNT_KEY, null)
        return if (stored.isNullOrEmpty()) DEFAULT_TAX_ACCOUNT else stored
    }

    fun setCurrency(context: Context, value: String) =
        prefs(context).edit().putString(CURRENCY_KEY, value).apply()

    fun setTaxAccount(context: Context, value: String) =
        prefs(context).edit().putString(TAX_ACCOUNT_KEY, value).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
