package com.zhenbo.beanbeaver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.ui.theme.bbCardFill
import com.zhenbo.beanbeaver.ui.theme.bbCardShadow
import com.zhenbo.beanbeaver.ui.theme.bbHairline
import com.zhenbo.beanbeaver.ui.theme.bbInk
import com.zhenbo.beanbeaver.ui.theme.bbInkSecondary

/**
 * The block at the top of Home and Spending: a card rounded on its top corners
 * only, with a torn paper edge along the bottom. Kotlin twin of iOS
 * `ReceiptSlip`.
 *
 * **One torn edge per screen, and this is it.** The receipt idea is carried by
 * the palette and by the mono figures; the tear is the single literal gesture,
 * and it earns its place only by being rare. A second one on a list card would
 * make both read as decoration.
 *
 * It also solves the complaint that started this: Home's month card used to sit
 * below a large blank band under the app bar. This starts at the top of the
 * content area, so the first thing on the screen is the number the app exists to
 * produce.
 */
@Composable
fun ReceiptSlip(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fill = bbCardFill
    val shadow = bbCardShadow
    Column(modifier = modifier.fillMaxWidth()) {
        val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 3.dp, shape = shape, spotColor = shadow)
                .clip(shape)
                .background(fill)
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
            content = content,
        )
        // Zero spacing above, deliberately: the seam has to be invisible, and any
        // gap at all turns one sheet of paper into two.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TornEdgeHeight)
                .shadow(elevation = 3.dp, shape = TornEdgeShape, spotColor = shadow)
                .background(fill, TornEdgeShape),
        )
    }
}

/**
 * Tall enough to read as torn at a glance, short enough not to become a design
 * element in its own right.
 */
val TornEdgeHeight = 11.dp

/** Distance between tooth tips, in dp. */
private const val TORN_EDGE_PITCH_DP = 15f

/**
 * The sawtooth strip under the slip: teeth pointing down, a straight top.
 *
 * A [Shape] rather than a repeating image or a stack of triangles so the shadow
 * follows the actual outline. That is the difference between a torn edge and a
 * strip of paper lying beneath the card — with a rectangular shadow the illusion
 * collapses immediately.
 */
val TornEdgeShape: Shape = object : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val pitch = with(density) { TORN_EDGE_PITCH_DP.dp.toPx() }
        val path = Path()
        path.moveTo(0f, 0f)
        // Runs past the trailing edge on purpose. A partial tooth at the end is
        // what a real tear looks like; stopping at the last whole one leaves a
        // flat run in the corner that reads as a mistake. The shape is clipped
        // by its bounds.
        var x = 0f
        while (x < size.width) {
            path.lineTo(x + pitch / 2f, size.height)
            path.lineTo(x + pitch, 0f)
            x += pitch
        }
        path.lineTo(size.width, 0f)
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * The micro-label above a figure: mono, uppercase, letter-spaced. iOS
 * `BBEyebrow`.
 *
 * Tracking is absolute, not em-relative — the design's `0.13em` at 11sp is
 * 1.43sp, and passing `0.13` would be a hairline's worth of spacing that looks
 * like a plain small label.
 */
@Composable
fun BbEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 11.sp,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = size,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = (size.value * 0.13f).sp,
        color = bbInkSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The eye that masks every money figure on the screen it sits on.
 *
 * Its own composable because Home and Spending both carry one and they must be
 * the same control — same glyph, same 44dp target, same single piece of state.
 * The Settings toggle writes that state too; three places, one value.
 *
 * Always present, never only-while-masked: it is a toggle, so hiding it after a
 * reveal would strand someone with no way back short of Settings.
 */
@Composable
fun AmountPrivacyEye(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hidden by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()
    IconButton(onClick = { AmountPrivacy.toggle(context) }, modifier = modifier) {
        Icon(
            if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (hidden) "Show amounts" else "Hide amounts",
            tint = bbInkSecondary,
        )
    }
}

/**
 * A money figure at display size: mono, tight, with the cents stepped back.
 *
 * The cents are drawn at 40% opacity rather than smaller. Shrinking them is the
 * other common treatment and it costs the alignment — a column of totals stops
 * lining up the moment two of them have differently-sized tails. Opacity keeps
 * the metrics and still says "the dollars are the number".
 *
 * Masked figures render whole: `$•••` has no cents to step back, and splitting
 * on a decimal point that isn't there would silently dim the last three
 * characters of the placeholder.
 */
@Composable
fun DisplayAmount(
    amount: Double,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    size: TextUnit = 46.sp,
    tracking: TextUnit = (-2).sp,
) {
    val text = maskedAmount(formatCurrency(amount), hidden)
    val ink = bbInk
    val dot = text.lastIndexOf('.')
    val body = if (dot >= 0 && !hidden) {
        buildAnnotatedString {
            append(text.substring(0, dot))
            withStyle(SpanStyle(color = ink.copy(alpha = 0.4f))) { append(text.substring(dot)) }
        }
    } else {
        buildAnnotatedString { append(text) }
    }
    Text(
        body,
        modifier = modifier,
        fontSize = size,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = tracking,
        color = ink,
        maxLines = 1,
    )
}

/**
 * A row divider inset from the leading edge only — the way a grouped list sets a
 * separator, so the rows read as one group rather than as a rule drawn across a
 * card.
 */
@Composable
fun BbHairline(modifier: Modifier = Modifier, startInset: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(1.dp)
            .background(bbHairline),
    )
}
