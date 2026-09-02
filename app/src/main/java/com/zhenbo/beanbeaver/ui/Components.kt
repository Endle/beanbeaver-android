package com.zhenbo.beanbeaver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.bbCardFill
import com.zhenbo.beanbeaver.ui.theme.bbCardShadow
import com.zhenbo.beanbeaver.ui.theme.bbExported
import com.zhenbo.beanbeaver.ui.theme.bbUnexported

/**
 * Card container: warm paper, 20dp corners, one soft shadow — the Kotlin twin of
 * iOS `bbCard()`. Content is laid out in a [ColumnScope] so callers stack fields
 * the way a SwiftUI `VStack` would inside `.bbCard()`.
 *
 * [padding] is a parameter because the redesign has rows that must reach the
 * card's edge — a divider inset 16dp from the leading edge only, and a
 * full-bleed rule above a "Show 4 more" control. Those cards pass `0.dp` and pad
 * their own rows. Everything else takes the default and looks as it did.
 */
@Composable
fun BbCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 3.dp, shape = shape, spotColor = bbCardShadow)
            .clip(shape)
            .background(bbCardFill)
            .padding(padding),
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
 * One receipt's export state as a single glyph — filled green for filed, a
 * hollow amber ring for a backlog. Kotlin twin of iOS `ExportStatusDot`.
 *
 * A ring rather than a second fill for [SpendRecord.ExportStatus.NOT_EXPORTED]:
 * the two states have to be tellable apart at 9dp *and* by someone who can't
 * separate the hues, so they differ in shape first and colour second.
 */
@Composable
fun ExportStatusDot(
    status: SpendRecord.ExportStatus,
    size: Dp = 9.dp,
    modifier: Modifier = Modifier,
) {
    val exported = status == SpendRecord.ExportStatus.EXPORTED
    val color = if (exported) bbExported else bbUnexported
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = status.label },
    ) {
        if (exported) {
            drawCircle(color)
        } else {
            // Inset by half the stroke so the ring's outer edge lands on the
            // same circle the filled dot draws — otherwise the two states are
            // visibly different sizes side by side in the chips.
            val stroke = 1.5.dp.toPx()
            drawCircle(color, radius = size.toPx() / 2 - stroke / 2, style = Stroke(stroke))
        }
    }
}

/**
 * A quiet accent pill: the shape this app uses for a secondary action sitting
 * inside a card row ("Export", "Set Up"/"Change"). Soft-red fill with accent
 * text, so it reads as tappable without competing with the screen's one filled
 * button.
 */
@Composable
fun BbPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(BbAccentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
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
