package com.beanbeaver.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Green accent roughly matching the iOS Theme “bbAccent” spirit.
private val Accent = Color(0xFF2E7D32)
private val AccentDark = Color(0xFF81C784)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Color(0xFF558B2F),
    tertiary = Color(0xFF00695C),
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    secondary = Color(0xFFAED581),
    tertiary = Color(0xFF80CBC4),
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
