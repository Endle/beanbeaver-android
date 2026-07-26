package com.zhenbo.beanbeaver.export

import android.content.Context

/**
 * User-facing options for what an export writes. Kotlin twin of iOS
 * `LedgerFileOptions`: the Settings toggle and `LedgerEntry.make` read the same
 * key, so every destination honours one setting rather than each carrying its
 * own. Default on — the details file is useful and the GitHub backend already
 * carried it unconditionally before this became a choice.
 */
object LedgerFileOptions {
    const val INCLUDE_DETAILS_JSON_KEY = "includeDetailsJSON"

    private const val PREFS = "beanbeaver"

    /** Whether a `.json` sidecar is written next to each exported receipt. */
    fun includeDetailsJson(context: Context): Boolean =
        prefs(context).getBoolean(INCLUDE_DETAILS_JSON_KEY, true)

    fun setIncludeDetailsJson(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(INCLUDE_DETAILS_JSON_KEY, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * A place a parsed receipt's beancount transaction can be sent. Kotlin twin of
 * iOS `LedgerDestinationKind`.
 *
 * Deliberately an enum rather than a set of ad-hoc booleans: the Sync screen is
 * a "pick one destination, show only its detail" picker, so growing a new target
 * is a case plus a `when` arm, not another stacked settings section.
 *
 * iOS also defines a `filesInbox` case (append to a `.bean` file reached through
 * the system document picker). It is commented out there — "disabled for now, it
 * will be back in a future version" — so there is deliberately no Storage Access
 * Framework twin here yet. Add one when iOS re-enables its own.
 */
enum class LedgerDestinationKind {
    GITHUB_PR;

    val title: String
        get() = when (this) {
            GITHUB_PR -> "GitHub pull request"
        }

    /** Compact label for the home screen's "Export:" indicator. */
    val shortTitle: String
        get() = when (this) {
            GITHUB_PR -> "GitHub"
        }

    /** One-line explainer shown under the row in settings. */
    val blurb: String
        get() = when (this) {
            GITHUB_PR ->
                "Open a pull request that appends the transaction to a file in your ledger's GitHub repo."
        }
}
