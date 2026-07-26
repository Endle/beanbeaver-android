package com.zhenbo.beanbeaver

import android.content.Context

/**
 * Single source of truth for whether premium features are unlocked. Every feature
 * gate reads [isPremium] and nothing reads the backing store directly, so turning
 * on real monetization later is a change here — no call site moves. The Money
 * Manager export is the first gated feature. Kotlin twin of iOS `Entitlements`.
 */
object Entitlements {
    /** SharedPreferences key behind the "Enable premium features" switch (Settings). */
    const val PREMIUM_ENABLED_KEY = "premiumEnabled"

    private const val PREFS = "beanbeaver"

    /**
     * ⚠️ STUB — replace with a real Play Billing entitlement check before any paid
     * release. Mirrors the iOS stub: there is nothing to buy yet, so premium is
     * simply a switch the tester controls, defaulting on so the beta audience has
     * it without hunting for the toggle.
     */
    fun isPremium(context: Context): Boolean =
        prefs(context).getBoolean(PREMIUM_ENABLED_KEY, true)

    fun setPremium(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(PREMIUM_ENABLED_KEY, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
