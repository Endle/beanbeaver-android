package com.beanbeaver.app.ui.theme

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
