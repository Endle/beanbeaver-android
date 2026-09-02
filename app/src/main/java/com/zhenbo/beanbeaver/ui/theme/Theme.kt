package com.zhenbo.beanbeaver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Brand accent — a legible red, not flag-saturated. The exact value iOS uses
 * (`Color.bbAccent` = sRGB 0.80, 0.11, 0.15), kept bit-for-bit so the two apps
 * read as the same product. iOS uses this fixed red in both light and dark, so
 * we do too.
 */
val BbAccent = Color(0xFFCC1C26)

/** Soft red tint for chips/banners over a card background (iOS `bbAccentSoft`). */
val BbAccentSoft = BbAccent.copy(alpha = 0.12f)

/**
 * "This receipt reached your ledger." Deliberately *not* [BbAccent]: red is the
 * tap-me colour (see `BbQuietButton`), and export state is a readout, never an
 * action. The exact values iOS uses, lifted for dark so both stay legible.
 */
val bbExported: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4DC770) else Color(0xFF248A3D)

/**
 * "Not filed yet." Amber rather than red because a backlog is a *pending*
 * state, not an error — nothing is wrong with a receipt you scanned two minutes
 * ago.
 */
val bbUnexported: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE89952) else Color(0xFFC7692A)

/**
 * The receipt-paper palette — **light mode only**, by design.
 *
 * The redesign puts the app's surfaces on warm paper rather than the system's
 * cool grey: a cream canvas, an off-white card, and a warm near-black ink. It is
 * the cheapest half of "this app is about receipts" — no illustration, no
 * texture, just a ground that isn't the Material default.
 *
 * **Every one of these is a pair, and the dark half is what the app used
 * before.** Dark mode was not designed, and a warm palette invented for it here
 * would be a guess that ships. So in dark each token resolves to the existing
 * value, which means a dark build is unchanged and a light build is the
 * redesign. Things derived from these — the torn edge, the hairlines — follow
 * automatically. Same rule iOS states in `Theme.swift`, same hex values.
 */
val bbCanvas: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF4F1EA)

/** The card fill. Replaces [cardBackground] on the redesigned surfaces. */
val bbCardFill: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFCFBF8)

/** Warm near-black — the primary label on the redesigned surfaces. */
val bbInk: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFFFFF) else Color(0xFF1A1815)

/**
 * Secondary text. **68% ink is the floor for anything under 18sp** — it clears
 * 4.5:1 on the card, and the lighter values the design tried first did not.
 * Don't reach for [bbInkTertiary] to quieten a label.
 */
val bbInkSecondary: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color(0xFFFFFFFF).copy(alpha = 0.60f)
        else Color(0xFF1A1815).copy(alpha = 0.68f)

/**
 * **Non-text only** — rules, chevrons, bar fills. It does not clear contrast for
 * body copy at any size, which is the whole reason [bbInkSecondary] stops at 68%.
 */
val bbInkTertiary: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color(0xFFFFFFFF).copy(alpha = 0.30f)
        else Color(0xFF1A1815).copy(alpha = 0.45f)

/** Row and card dividers — a hair, not a `HorizontalDivider()`. */
val bbHairline: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color(0xFFFFFFFF).copy(alpha = 0.15f)
        else Color(0xFF1A1815).copy(alpha = 0.09f)

/**
 * One shadow, defined once, because the torn edge has to carry the *same* one —
 * a strip with its own shadow reads as a second sheet of paper lying under the
 * first.
 */
val bbCardShadow: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color.Black.copy(alpha = 0.30f)
        else Color(0xFF1A1815).copy(alpha = 0.07f)

/** The floor a trend chart is read against. */
val bbChartBaseline: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color(0xFFFFFFFF).copy(alpha = 0.20f)
        else Color(0xFF3C3C43).copy(alpha = 0.14f)

/**
 * The scan-result impact chip: "this is what the scan did to your month".
 *
 * Green because it is a confirmation, but deliberately **not** [bbExported],
 * which means "reached your ledger" and nothing else — a receipt can land in
 * your month without going anywhere near a ledger.
 */
val bbImpactText: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6BD185) else Color(0xFF166B2C)

val bbImpactSoft: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color(0xFF248A3D).copy(alpha = 0.22f)
        else Color(0xFF248A3D).copy(alpha = 0.10f)

/**
 * iOS "grouped" surfaces, ported so cards sit on a slightly recessed page the
 * way they do on iPhone: [groupedBackground] is the page (systemGroupedBackground),
 * [cardBackground] is the raised card (secondarySystemGroupedBackground).
 */
val groupedBackground: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF2F2F7)

val cardBackground: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = BbAccent,
    onPrimary = Color.White,
    secondary = BbAccent,
    tertiary = Color(0xFFB26A00),
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = BbAccent,
    onPrimary = Color.White,
    secondary = BbAccent,
    tertiary = Color(0xFFE0A030),
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
)

@Composable
fun BeanBeaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
