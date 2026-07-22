package com.beanbeaver.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beanbeaver.app.ui.theme.BbAccentSoft
import com.beanbeaver.app.ui.theme.cardBackground

/**
 * Card container: raised surface, rounded corners, soft shadow — the Kotlin twin
 * of iOS `bbCard()`. Content is laid out in a [ColumnScope] so callers stack
 * fields the way a SwiftUI `VStack` would inside `.bbCard()`.
 */
@Composable
fun BbCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 3.dp, shape = shape, spotColor = Color.Black.copy(alpha = 0.10f))
            .clip(shape)
            .background(cardBackground)
            .padding(16.dp),
        content = content,
    )
}

/**
 * The quiet tier (iOS `BBQuietButtonStyle`): valid actions we don't want to
 * advertise — Settings, More. An outlined pill rather than a fill, so it can't be
 * mistaken for a disabled button and it keeps the pill rhythm of the button stack.
 */
@Composable
fun BbQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * An iOS-style grouped section: an optional uppercase header, a rounded [BbCard]
 * of rows, and an optional quiet footer explaining the setting. Shared by the
 * Settings, GitHub-sync, and debug-info screens so they read as one system.
 */
@Composable
fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        BbCard(content = content)
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The accent chip for an item's most-specific category tag — soft red pill,
 * accent text (iOS `tagRow`'s primary chip).
 */
@Composable
fun CategoryChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(BbAccentSoft)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
