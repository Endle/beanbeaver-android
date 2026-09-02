package com.zhenbo.beanbeaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.bbChartBaseline
import com.zhenbo.beanbeaver.ui.theme.bbInk
import com.zhenbo.beanbeaver.ui.theme.bbInkSecondary
import com.zhenbo.beanbeaver.ui.theme.bbInkTertiary
import uniffi.bb_mobile_ffi.SpendTrend
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Six weeks of spending, drawn as bars. Kotlin twin of iOS `TrendBars`.
 *
 * **Bars because the series is six discrete totals.** A line implies you could
 * read a value between two points, and there is nothing between two weekly
 * buckets to read; it also hides the thing most worth seeing, which is that the
 * newest bucket is a *partial* week. A bar that is visibly shorter because the
 * week is three days old reads as three days old. The line version was drawn
 * side by side on iOS and rejected.
 *
 * A bar's height encodes dollars, so the **caller** hides the whole chart while
 * amounts are masked rather than normalising it — see [TrendChartMasked], which
 * is the shared placeholder so toggling the eye can't make the card jump.
 */
@Composable
fun TrendBars(
    amounts: List<Double>,
    labels: List<String> = emptyList(),
    height: Dp = 62.dp,
    modifier: Modifier = Modifier,
) {
    // Bars are read against each other, not against a zero that is off-screen,
    // so the tallest fills the frame. A series of identical weeks therefore
    // draws six full-height bars, which is the honest picture: they *are* the
    // same.
    val scale = maxOf(amounts.maxOrNull() ?: 0.0, 0.01)
    val quiet = bbInk.copy(alpha = 0.16f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Weekly spending, six weeks" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            amounts.forEachIndexed { index, amount ->
                val isCurrent = index == amounts.lastIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // A floor of 2dp, so a week with nothing in it draws a
                        // seat rather than a gap. An absent bar reads as missing
                        // data; a flat one reads as a quiet week, which is what
                        // it is.
                        .height(maxOf(height * (amount / scale).toFloat(), 2.dp))
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isCurrent) BbAccent else quiet),
                )
            }
        }

        if (labels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (index == labels.lastIndex) BbAccent else bbInkSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/**
 * What stands in for the chart while amounts are masked.
 *
 * A blank of the same height rather than nothing at all, so toggling the eye
 * doesn't make the card jump — and so the card doesn't look broken in the
 * default state, which *is* masked.
 */
@Composable
fun TrendChartMasked(height: Dp = 62.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(bbChartBaseline.copy(alpha = 0.35f))
            .semantics { contentDescription = "Spending trend hidden" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = bbInkTertiary)
    }
}

/**
 * The delta figure as both screens draw it: accent when there is a change, quiet
 * when there isn't. Kotlin twin of iOS `TrendDeltaLabel`.
 *
 * A composable rather than a string so the colour rule travels with the wording.
 */
@Composable
fun TrendDeltaLabel(trend: SpendTrend, hidden: Boolean, modifier: Modifier = Modifier) {
    Text(
        trend.deltaText(hidden),
        modifier = modifier,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = if (trend.isFlat) bbInkSecondary else BbAccent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Whether the delta is worth calling a change. The crate rounds to cents, so this
 * is an exact test rather than an epsilon.
 */
val SpendTrend.isFlat: Boolean get() = delta == 0.0

/** The series as plain numbers, oldest first, for drawing. */
val SpendTrend.amounts: List<Double> get() = points.map { it.amount }

/**
 * How the weekly series reads — the axis labels and the delta sentence.
 *
 * **Here rather than on each screen** because Home and Spending both draw this
 * series, and the two must not be able to word the same buckets differently.
 * They already did once on iOS: one said `↑ $225.06` and the other `+$225.06 vs
 * last wk` for the same number.
 */
val SpendTrend.weekLabels: List<String>
    get() {
        val format = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        return points.mapIndexed { index, point ->
            // `now` rather than a date, because the last bucket is the week
            // *containing* today: labelling it with its start date invites
            // reading the bar as a finished week, which is exactly the
            // misreading bars are here to avoid.
            if (index == points.lastIndex) {
                "now"
            } else {
                runCatching {
                    LocalDate.of(
                        point.range.start.year,
                        point.range.start.month.toInt(),
                        point.range.start.day.toInt(),
                    ).format(format)
                }.getOrDefault("")
            }
        }
    }

/**
 * The week-over-week delta, signed and masked.
 *
 * A `+`/`−` rather than an arrow: the figure sits inches from a chart whose bars
 * already point, and two directional signals disagree in a way a sign cannot.
 * "No change" gets words, because `+$0.00` is what an unrounded float would have
 * rendered forever.
 */
fun SpendTrend.deltaText(hidden: Boolean): String {
    if (isFlat) return "No change"
    val sign = if (delta > 0) "+" else "−"
    return "$sign${maskedAmount(formatCurrency(kotlin.math.abs(delta)), hidden)} vs last wk"
}

/**
 * `"Aug 1–21"`, or `"Aug 28 – Sep 3"` when the span crosses a month.
 *
 * An en dash, and tight against the numbers within a month — that is how a date
 * range is set. Spaced when the two sides are two words each, because
 * `Aug 28–Sep 3` reads as one token.
 *
 * `end` is exclusive, so the label names the day before it.
 */
val uniffi.bb_mobile_ffi.SpendDateRange.shortLabel: String
    get() = runCatching {
        val from = LocalDate.of(start.year, start.month.toInt(), start.day.toInt())
        val lastDay = LocalDate.of(end.year, end.month.toInt(), end.day.toInt()).minusDays(1)
        val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val day = DateTimeFormatter.ofPattern("d", Locale.US)
        // The month name is repeated only when it changes. Both windows this
        // renders sit inside one month in every ordinary case, so the two-month
        // form is the defensive branch rather than the common one.
        if (from.year == lastDay.year && from.month == lastDay.month) {
            "${from.format(monthDay)}–${lastDay.format(day)}"
        } else {
            "${from.format(monthDay)} – ${lastDay.format(monthDay)}"
        }
    }.getOrDefault("")
