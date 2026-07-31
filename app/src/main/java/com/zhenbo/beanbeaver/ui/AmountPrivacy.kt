package com.zhenbo.beanbeaver.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether money figures are shown or masked on the glanceable surfaces — the
 * home card and the spending screens. Kotlin twin of iOS `AmountPrivacy`.
 *
 * Exists because the home screen states a month's total, in full, on launch,
 * before any authentication: anyone glancing at the phone reads it. That sits
 * badly against an app whose pitch is that your spending is nobody's business, so
 * this makes the promise something the UI actually does rather than only says.
 *
 * **One piece of state**, deliberately. An earlier iOS version had a persisted
 * preference plus a session-only "reveal", and it read as a bug: tapping the eye
 * on the home card showed the figures while Settings still said "Hide amounts"
 * was on. Two controls over what looks like one thing have to agree, and the
 * honest way to make them agree is for there to be only one thing — so the eye
 * *is* the setting.
 *
 * **On by default**, for the same reason: not showing a number that turns out to
 * matter is a far cheaper mistake than having already shown it to the room, and
 * one tap of the eye undoes it.
 */
object AmountPrivacy {
    private const val PREFS = "beanbeaver"
    private const val KEY_HIDE = "hideAmounts"

    /**
     * What a masked figure reads as. Same `$` the rest of the app hardcodes (see
     * [formatCurrency]), so a masked column still lines up with an unmasked one.
     */
    const val PLACEHOLDER = "$•••"

    private val _hideAmounts = MutableStateFlow(true)

    /**
     * The one piece of state, and what both the eye and the Settings toggle
     * write. Written through to SharedPreferences on change rather than read at
     * compute time, so this stays the single source of truth.
     */
    val hideAmounts: StateFlow<Boolean> = _hideAmounts.asStateFlow()

    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        // `getBoolean` needs the on-by-default spelled out rather than assumed,
        // since an unset key would otherwise read false.
        _hideAmounts.value = prefs(context).getBoolean(KEY_HIDE, true)
    }

    fun set(context: Context, hide: Boolean) {
        ensureLoaded(context)
        _hideAmounts.value = hide
        prefs(context).edit().putBoolean(KEY_HIDE, hide).apply()
    }

    /** What the eye does, wherever it appears — the same write the Settings toggle performs. */
    fun toggle(context: Context) = set(context, !_hideAmounts.value)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The figure as it should appear. Every money string on a glanceable surface goes
 * through here, so a screen can't half-mask.
 */
fun maskedAmount(formatted: String, hidden: Boolean): String =
    if (hidden) AmountPrivacy.PLACEHOLDER else formatted
