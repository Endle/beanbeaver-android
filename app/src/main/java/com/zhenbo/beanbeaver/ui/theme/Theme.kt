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
