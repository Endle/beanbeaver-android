package com.zhenbo.beanbeaver.receipt

import android.content.Context

/**
 * Stored budget configuration: which tracked root carries a monthly target, and
 * what that target is. Mirrors `LedgerFormatPrefs` — a couple of
 * SharedPreferences-backed settings read at compute time, so a change in
 * Settings takes effect on the very next render.
 *
 * Deliberately *not* an input to `SpendSummary`: the spend arithmetic is the
 * product and stands on its own, while a target is an optional overlay one
 * screen draws on top of it. Nothing here can change a number.
 */
object BudgetPrefs {
    private const val PREFS = "beanbeaver"
    private const val KEY_ROOT = "budgetRootTag"
    private const val KEY_AMOUNT = "budgetMonthlyAmount"

    /** Fallback when nothing is declared and nothing is stored — the app's most
     *  common use case names it directly rather than falling back to an arbitrary
     *  first tag. */
    private const val FALLBACK_ROOT = "grocery"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Root tags the current rule corpus actually declares, first-path-segment
     * only, in the order `RuleBook.tags()` returns them, de-duplicated. What the
     * root picker offers — never a hardcoded category list.
     */
    fun declaredRoots(context: Context): List<String> {
        ItemRuleStore.ensureLoaded(context)
        val seen = LinkedHashSet<String>()
        ItemRuleStore.ruleBook?.tags()?.forEach { tag ->
            val root = tag.path.substringBefore("/")
            if (root.isNotEmpty()) seen.add(root)
        }
        return seen.toList()
    }

    /**
     * The target's root tag: the user's stored choice if the corpus still
     * declares it, else [FALLBACK_ROOT] if that's declared, else whatever the
     * corpus declares first. Never empty as long as the corpus declares anything.
     */
    fun root(context: Context): String {
        val roots = declaredRoots(context)
        val stored = prefs(context).getString(KEY_ROOT, null)
        if (stored != null && stored in roots) return stored
        if (FALLBACK_ROOT in roots) return FALLBACK_ROOT
        return roots.firstOrNull() ?: FALLBACK_ROOT
    }

    fun setRoot(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ROOT, value).apply()
    }

    /**
     * The monthly target, or null for tracking-only — the default, and a complete
     * way to use the app. `0` and unset both read as null, since a $0 target has
     * no meaningful bar to draw.
     */
    fun monthlyAmount(context: Context): Double? {
        val value = prefs(context).getFloat(KEY_AMOUNT, 0f)
        return if (value > 0f) value.toDouble() else null
    }

    fun setMonthlyAmount(context: Context, value: Double?) {
        prefs(context).edit().apply {
            if (value != null && value > 0) putFloat(KEY_AMOUNT, value.toFloat())
            else remove(KEY_AMOUNT)
        }.apply()
    }
}

/**
 * Whether money figures are shown or masked on the glanceable surfaces — the
 * home card and the spending screens. Kotlin twin of iOS `AmountPrivacy`.
 *
 * Exists because the home screen states a month's total, in full, on launch,
 * before any authentication: anyone glancing at the phone reads it. That sits
 * badly against an app whose pitch is that your spending is nobody's business,
 * so this makes the promise something the UI actually does rather than only says.
 *
 * **One piece of state**, deliberately — the eye on the home card, the eye on
 * the Spending toolbar and the Settings toggle all write the same stored value,
 * so they can't disagree. **On by default**, for the same reason: not showing a
 * number that turns out to matter is a far cheaper mistake than having already
 * shown it to the room, and one tap of the eye undoes it.
 */
object AmountPrivacy {
    private const val PREFS = "beanbeaver"

    /** The one stored preference. */
    const val HIDE_KEY = "hideAmounts"

    /** What a masked figure reads as. Same `$` the rest of the app hardcodes. */
    const val PLACEHOLDER = "$•••"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hideAmounts(context: Context): Boolean = prefs(context).getBoolean(HIDE_KEY, true)

    fun setHideAmounts(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(HIDE_KEY, value).apply()
    }

    fun toggle(context: Context) = setHideAmounts(context, !hideAmounts(context))

    /** The figure as it should appear. Every money string on a glanceable surface
     *  goes through here, so a screen can't half-mask. */
    fun text(context: Context, formatted: String): String =
        if (hideAmounts(context)) PLACEHOLDER else formatted
}
