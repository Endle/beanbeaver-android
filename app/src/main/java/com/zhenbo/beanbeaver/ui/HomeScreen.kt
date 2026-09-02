package com.zhenbo.beanbeaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.exported
import com.zhenbo.beanbeaver.receipt.lastExportedAt
import com.zhenbo.beanbeaver.receipt.reachedTargets
import com.zhenbo.beanbeaver.receipt.unexported
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.bbInk
import com.zhenbo.beanbeaver.ui.theme.bbInkSecondary
import com.zhenbo.beanbeaver.ui.theme.bbInkTertiary

/**
 * The home screen: the one number the app exists to produce, the state of the
 * backlog, and the way into everything else. Kotlin twin of iOS `HomeView`.
 *
 * # What changed, and why
 *
 * This used to be a **launcher** — a spend card, a receipts card, two buttons and
 * a footnote. Two complaints followed from that, and both are answered here
 * rather than by moving things around:
 *
 * 1. **The top of the screen was empty.** The month card sat below a title and a
 *    tagline. The header slip now starts at the top of the content area, so the
 *    first thing on screen is the total.
 * 2. **There was no bottom navigation.** Spending, Receipts, Import and Settings
 *    were all boolean-gated screens off this one. Scan and Settings moved into a
 *    real tab bar ([RootTab]), and what is left here is a destinations card —
 *    four rows, each saying what is behind it *and* its current count, which a
 *    button never did.
 */
@Composable
fun HomeScreen(
    batchCount: Int,
    exportReady: Boolean,
    onOpenSpending: () -> Unit,
    onOpenReceipts: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSync: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val records by SpendStore.records.collectAsStateWithLifecycle()
    val hidden by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (records.isEmpty()) {
            EmptyHome(batchCount = batchCount, onScan = onScan, onOpenImport = onOpenImport)
        } else {
            LoadedHome(
                records = records,
                hidden = hidden,
                batchCount = batchCount,
                exportReady = exportReady,
                onOpenSpending = onOpenSpending,
                onOpenReceipts = onOpenReceipts,
                onOpenImport = onOpenImport,
                onOpenSync = onOpenSync,
            )
        }

        // Pinned below the column rather than floating under the last card: a
        // footnote that lands mid-screen on a short list reads as a caption for
        // whatever is above it.
        Spacer(Modifier.height(28.dp))
        PrivacyFootnote()
    }
}

@Composable
private fun LoadedHome(
    records: List<SpendRecord>,
    hidden: Boolean,
    batchCount: Int,
    exportReady: Boolean,
    onOpenSpending: () -> Unit,
    onOpenReceipts: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val monthId = remember(records) { SpendSummary.defaultMonthId(records) }
    val month = remember(records, monthId) { SpendSummary.month(monthId, records) }
    val facts = remember(records, monthId) { SpendSummary.facts(monthId, records) }

    HeaderSlip(
        month = month,
        facts = facts,
        hidden = hidden,
        onOpenSpending = onOpenSpending,
    )

    Spacer(Modifier.height(18.dp))

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (SpendSummary.SHOW_WEEKLY_TREND) {
            WeeklyCard(records = records, hidden = hidden)
        }
        DestinationsCard(
            records = records,
            batchCount = batchCount,
            exportReady = exportReady,
            onOpenSpending = onOpenSpending,
            onOpenReceipts = onOpenReceipts,
            onOpenImport = onOpenImport,
            onOpenSync = onOpenSync,
        )
    }
}

/**
 * The slip: the window, the total, and two measured figures.
 *
 * **Both figures are things nothing else on the screen carries.** What they
 * replaced was two lines that said what was already said — "tracked spend"
 * names the metric, which is the one thing a spend tracker doesn't need to say.
 *
 * A projected "on pace for $3,900" was tried here on iOS and **cut**. It is the
 * only figure on the screen that isn't measured, and it swings wildly for the
 * first week of every month. What sits in its place is what the same stretch of
 * last month actually came to, which answers the same question with a number
 * that already happened.
 */
@Composable
private fun HeaderSlip(
    month: SpendSummary.Month,
    facts: uniffi.bb_mobile_ffi.SpendMonthFacts,
    hidden: Boolean,
    onOpenSpending: () -> Unit,
) {
    ReceiptSlip {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The window and the count, not the word "Tracked" — the app tracks,
            // which isn't news. What a reader doesn't know is *which days* the
            // number below covers.
            BbEyebrow(
                "${facts.window.shortLabel} · ${month.receiptCount} " +
                    "receipt${if (month.receiptCount == 1) "" else "s"}",
                modifier = Modifier.weight(1f),
            )
            // Cancels the touch target's own padding so the glyph sits in the
            // slip's corner rather than inset from it. An offset, not negative
            // padding — `Modifier.padding` requires a non-negative value and
            // throws at composition, which a preview would not have caught.
            AmountPrivacyEye(modifier = Modifier.offset(x = 12.dp))
        }

        Spacer(Modifier.height(12.dp))

        DisplayAmount(
            amount = month.tracked,
            hidden = hidden,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSpending),
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Avg ${maskedAmount(formatCurrency(facts.dailyAverage), hidden)}/day",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = bbInkSecondary,
                maxLines = 1,
            )
            Box(
                Modifier
                    .padding(horizontal = 8.dp)
                    .size(width = 1.dp, height = 11.dp)
                    .background(bbInk.copy(alpha = 0.2f)),
            )
            Text(
                "${facts.previousWindow.shortLabel} " +
                    maskedAmount(formatCurrency(facts.previousTotal), hidden),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = bbInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WeeklyCard(records: List<SpendRecord>, hidden: Boolean) {
    val trend = remember(records) { SpendSummary.trend(records = records) }
    BbCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BbEyebrow("Weekly spend")
            // The eyebrow is a fixed label anyone can guess; the figure is the
            // news, so the spacer yields to it rather than the other way round.
            Spacer(Modifier.weight(1f))
            TrendDeltaLabel(trend = trend, hidden = hidden)
        }
        Spacer(Modifier.height(10.dp))
        // The bars encode dollars in their heights, so masking hides the chart
        // rather than only the figures beside it.
        if (hidden) {
            TrendChartMasked(height = 62.dp)
        } else {
            TrendBars(amounts = trend.amounts, labels = trend.weekLabels)
        }
    }
}

/**
 * One card, four rows — where the buttons and pills went.
 *
 * A row says what is behind it *and* how much is there, which a button could not:
 * "Receipts 22", "20 waiting to export". That count is the reason the row exists,
 * and it is why Export is a row here rather than a pill — it was a status readout
 * wearing a button before.
 */
@Composable
private fun DestinationsCard(
    records: List<SpendRecord>,
    batchCount: Int,
    exportReady: Boolean,
    onOpenSpending: () -> Unit,
    onOpenReceipts: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val backlog = records.unexported.size
    BbCard(padding = 0.dp) {
        DestinationRow(title = "Spending", trailing = "by category", onClick = onOpenSpending)
        BbHairline()
        DestinationRow(title = "Receipts", trailing = "${records.size}", onClick = onOpenReceipts)
        BbHairline()
        ExportRow(
            records = records,
            backlog = backlog,
            exportReady = exportReady,
            onOpenReceipts = onOpenReceipts,
            onOpenSync = onOpenSync,
        )
        BbHairline()
        DestinationRow(
            title = "Import from Photos",
            trailing = if (batchCount == 0) null else "$batchCount waiting",
            accented = batchCount > 0,
            onClick = onOpenImport,
        )
    }
}

@Composable
private fun DestinationRow(
    title: String,
    trailing: String?,
    accented: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `clickable` around the padded row rather than its content, so the
            // hit region is the padded band. A row leading somewhere different
            // from its neighbour makes a near miss a wrong answer, not a no-op.
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(title, fontSize = 17.sp, color = bbInk, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = if (accented) BbAccent else bbInkSecondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = bbInkTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The backlog row, in the three states it has to carry.
 *
 * It is a destination rather than a button because that is what it always was:
 * "20 waiting to export" is a readout, and the tap is "show me them". A backlog
 * wants the receipts in front of you before a batch goes out; anything else wants
 * the destination page.
 */
@Composable
private fun ExportRow(
    records: List<SpendRecord>,
    backlog: Int,
    exportReady: Boolean,
    onOpenReceipts: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val filedSomething = records.lastExportedAt != null
    val title = when {
        backlog > 0 -> "$backlog waiting to export"
        filedSomething -> "All receipts filed"
        // Nothing filed and nothing waiting: the setup prompt, and the only route
        // to Sync from this screen.
        exportReady -> "Exports to GitHub"
        else -> "No export destination yet"
    }
    DestinationRow(
        title = title,
        trailing = when {
            backlog > 0 -> "Export"
            exportReady -> "Change"
            else -> "Set Up"
        },
        accented = true,
        leading = when {
            backlog > 0 -> {
                { ExportStatusDot(SpendRecord.ExportStatus.NOT_EXPORTED) }
            }
            filedSomething -> {
                { ExportStatusDot(SpendRecord.ExportStatus.EXPORTED) }
            }
            else -> null
        },
        onClick = if (backlog > 0) onOpenReceipts else onOpenSync,
    )
}

/**
 * Nothing scanned: the slip and the cards are hidden and scanning is the whole
 * screen. A slip reading `$0.00` over an empty chart is worse than no slip at
 * all, and the first move is the camera anyway.
 */
@Composable
private fun EmptyHome(batchCount: Int, onScan: () -> Unit, onOpenImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            tint = BbAccent,
            modifier = Modifier.size(44.dp),
        )
        Text(
            "Scan your first receipt",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = bbInk,
        )
        Text(
            "Its items are read, sorted into categories, and added to the month — " +
                "on this phone.",
            fontSize = 15.sp,
            color = bbInkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Button(onClick = onScan) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan a Receipt", fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (batchCount == 0) "Import from Photos" else "Import from Photos · $batchCount waiting",
            style = MaterialTheme.typography.bodyMedium,
            color = BbAccent,
            modifier = Modifier.clickable(onClick = onOpenImport).padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun PrivacyFootnote() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = bbInkSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Scanned and parsed on your device. Nothing leaves it unless you export.",
            fontSize = 12.sp,
            color = bbInkSecondary,
        )
    }
}

/**
 * What the export row says once something has been filed — kept as a function
 * because the Receipts screen's footer says the same thing.
 *
 * Reports what actually happened when anything has, including both targets when
 * a receipt went to both.
 */
internal fun exportStatusLine(records: List<SpendRecord>, exportReady: Boolean): String {
    val last = records.lastExportedAt
        ?: return if (exportReady) "Exports to GitHub" else "No export destination yet"
    val targets = records.reachedTargets
    val where = if (targets.isEmpty()) "" else " to ${targets.joinToString(" and ")}"
    return "${records.exported.size} filed$where · last export ${friendlyDay(last)}"
}
