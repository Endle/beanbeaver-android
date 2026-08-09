package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.BudgetPrefs
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.unexported
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import java.time.LocalDate

/**
 * Where a month's money went, computed from scanned receipts' *items* rather than
 * their totals (see [SpendSummary]). Kotlin twin of iOS `SpendingView`.
 *
 * A spend tracker first: the headline is everything tracked, and the breakdown is
 * every category the classifier reached, largest first. A monthly target is an
 * optional overlay — when [BudgetPrefs.monthlyAmount] is unset, nothing
 * budget-shaped renders at all and the screen is complete without it.
 *
 * Read-only over receipts: nothing here edits one, only the target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(
    /**
     * Opens the scanner — the empty state's action, since a spending screen
     * reached with nothing scanned has nothing else useful to offer.
     */
    onScan: () -> Unit,
    onOpenReceipts: (monthFilter: String?) -> Unit,
    onOpenCategory: (category: SpendSummary.Category, title: String, monthId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    val records by SpendStore.records.collectAsStateWithLifecycle()
    val hidden by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()

    var selectedMonthId by rememberSaveable { mutableStateOf<String?>(null) }
    var monthlyAmount by remember { mutableStateOf(BudgetPrefs.monthlyAmount(context)) }
    var showAmountDialog by rememberSaveable { mutableStateOf(false) }

    val monthIds = remember(records) { SpendSummary.monthIds(records) }
    val activeMonthId = selectedMonthId ?: SpendSummary.defaultMonthId(records)
    val summary = remember(records, activeMonthId) { SpendSummary.month(activeMonthId, records) }
    // Only ever used to decorate one group — never to decide what the screen counts.
    val targetRoot = remember(records) { BudgetPrefs.root(context) }
    val isCurrentMonth = activeMonthId == SpendSummary.currentMonthId()

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                // The screen's name, not the month it happens to be showing — the
                // stepper below says which month, and it can page away from this one.
                title = { Text("Spending") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        // The same single state the home card's eye and the
                        // Settings toggle write — three places, one value.
                        IconButton(onClick = { AmountPrivacy.toggle(context) }) {
                            Icon(
                                if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (hidden) "Show amounts" else "Hide amounts",
                            )
                        }
                        IconButton(onClick = { onOpenReceipts(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "All receipts")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            EmptySpending(modifier = Modifier.fillMaxSize().padding(padding), onScan = onScan)
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MonthStepper(
                label = summary.label,
                // monthIds is newest-first, so "older" moves to a higher index
                // and "newer" moves to a lower one.
                canGoOlder = monthIds.indexOf(activeMonthId).let { it >= 0 && it + 1 in monthIds.indices },
                canGoNewer = monthIds.indexOf(activeMonthId).let { it >= 0 && it - 1 in monthIds.indices },
                onOlder = {
                    val i = monthIds.indexOf(activeMonthId)
                    if (i + 1 in monthIds.indices) selectedMonthId = monthIds[i + 1]
                },
                onNewer = {
                    val i = monthIds.indexOf(activeMonthId)
                    if (i - 1 in monthIds.indices) selectedMonthId = monthIds[i - 1]
                },
            )

            Headline(
                tracked = maskedAmount(formatCurrency(summary.tracked), hidden),
                receiptCount = summary.records.size,
                // This month's unfiled receipts — the same `isExported` split
                // the Receipts screen's dots and chips draw, scoped to the
                // month on screen.
                backlogCount = summary.records.unexported.size,
                onOpenReceipts = { onOpenReceipts(activeMonthId) },
            )

            summary.roots.forEach { group ->
                RootCard(
                    group = group,
                    maxLeafAmount = summary.maxLeafAmount,
                    hidden = hidden,
                    target = monthlyAmount.takeIf { group.id == targetRoot },
                    isCurrentMonth = isCurrentMonth,
                    onEditTarget = { showAmountDialog = true },
                    onOpenRoot = {
                        onOpenCategory(SpendSummary.Category.Root(group.id), group.label, activeMonthId)
                    },
                    onOpenLeaf = { leaf ->
                        onOpenCategory(SpendSummary.Category.Leaf(leaf.label), leaf.label, activeMonthId)
                    },
                )
            }

            ReconciliationCard(summary, hidden)

            if (monthlyAmount == null) {
                // Offered once, quietly, at the bottom — a target is opt-in and
                // the screen is complete without one.
                BbCard(modifier = Modifier.clickable { showAmountDialog = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Set a Monthly Budget",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showAmountDialog) {
        BudgetAmountDialog(
            initial = monthlyAmount,
            rootLabel = targetRoot.replaceFirstChar { it.uppercase() },
            onDismiss = { showAmountDialog = false },
            onSave = {
                BudgetPrefs.setMonthlyAmount(context, it)
                monthlyAmount = it
                showAmountDialog = false
            },
        )
    }
}

@Composable
private fun EmptySpending(modifier: Modifier = Modifier, onScan: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing Tracked Yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scan a receipt and its items show up here, sorted into categories.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onScan) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan a Receipt", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MonthStepper(
    label: String,
    canGoOlder: Boolean,
    canGoNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOlder, enabled = canGoOlder) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Older month")
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNewer, enabled = canGoNewer) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Newer month")
        }
    }
}

/**
 * Everything tracked this month, and the way through to the receipts behind it.
 * The count is the tap target rather than inert text — it's the most natural
 * place to reach for when you want to see what made up the number.
 */
@Composable
private fun Headline(
    tracked: String,
    receiptCount: Int,
    backlogCount: Int,
    onOpenReceipts: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Label colour, not accent: red on a 44sp money total reads as an
        // alarm, and "tracked spend" is not an alarm. Accent is reserved for
        // things you can tap — the link below it, and the target bar.
        Text(
            tracked,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "tracked spend",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.clickable(onClick = onOpenReceipts).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$receiptCount receipt${if (receiptCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (backlogCount > 0) {
                Spacer(Modifier.width(6.dp))
                ExportStatusDot(SpendRecord.ExportStatus.NOT_EXPORTED, size = 7.dp)
                Spacer(Modifier.width(4.dp))
                Text("$backlogCount not exported", style = MaterialTheme.typography.labelMedium)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * One top-level category: its total, then the leaves beneath it. The group
 * carrying a monthly target — and only that one — also draws the target's bar and
 * pace line, so a budget reads as an annotation on the spending rather than as the
 * point of the screen.
 */
@Composable
private fun RootCard(
    group: SpendSummary.RootGroup,
    maxLeafAmount: Double,
    hidden: Boolean,
    target: Double?,
    isCurrentMonth: Boolean,
    onEditTarget: () -> Unit,
    onOpenRoot: () -> Unit,
    onOpenLeaf: (SpendSummary.Leaf) -> Unit,
) {
    BbCard {
        // Header and leaves both drill into the items behind the figure — the
        // question a tapped total actually raises. `clickable` wraps the padded
        // row rather than its content, so the whole band is the touch target
        // instead of just the glyphs.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRoot)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                maskedAmount(formatCurrency(group.amount), hidden),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (target != null) {
            Spacer(Modifier.size(4.dp))
            TargetBar(
                spent = group.amount,
                target = target,
                hidden = hidden,
                isCurrentMonth = isCurrentMonth,
                onEditTarget = onEditTarget,
            )
            Spacer(Modifier.size(8.dp))
        }

        // No spacing between leaves: each row carries its gap as padding instead,
        // so the space between two leaves is *tappable* and belongs to one of them
        // rather than being a dead band between rows leading to different
        // categories, where a near miss is a wrong answer rather than a no-op.
        group.leaves.forEach { leaf ->
            LeafRow(
                leaf = leaf,
                maxAmount = maxLeafAmount,
                hidden = hidden,
                onClick = { onOpenLeaf(leaf) },
            )
        }
    }
}

/**
 * Leaf bars scale to the largest leaf anywhere in the month, so a bar means the
 * same thing in every card on the screen.
 */
@Composable
private fun LeafRow(
    leaf: SpendSummary.Leaf,
    maxAmount: Double,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(leaf.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                maskedAmount(formatCurrency(leaf.amount), hidden),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        // Neutral fill, not accent. Every category on the screen drawn in alarm
        // red makes the one bar that *is* a judgement — the target bar above,
        // which can actually go over — indistinguishable from a dozen bars that
        // are just measurements.
        ProportionBar(
            fraction = if (maxAmount > 0) leaf.amount / maxAmount else 0.0,
            height = 6.dp,
            track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            fill = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TargetBar(
    spent: Double,
    target: Double,
    hidden: Boolean,
    isCurrentMonth: Boolean,
    onEditTarget: () -> Unit,
) {
    val remaining = target - spent
    val fraction = if (target > 0) spent / target else 0.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProportionBar(
            fraction = fraction,
            height = 10.dp,
            track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            fill = if (fraction > 1) MaterialTheme.colorScheme.error else BbAccent,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (remaining >= 0) {
                    "${maskedAmount(formatCurrency(remaining), hidden)} left"
                } else {
                    "${maskedAmount(formatCurrency(-remaining), hidden)} over"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "of ${maskedAmount(formatCurrency(target), hidden)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onEditTarget).padding(4.dp),
            )
        }
        if (isCurrentMonth) {
            // Spend-to-date against day-of-month — what makes a target actionable
            // rather than retrospective. Current month only; for a past month the
            // month's own total is the whole answer.
            val today = LocalDate.now()
            val day = today.dayOfMonth
            val daysInMonth = today.lengthOfMonth()
            val delta = target * day / daysInMonth - spent
            Text(
                if (delta >= 0) {
                    "day $day of $daysInMonth · ${maskedAmount(formatCurrency(delta), hidden)} ahead of pace"
                } else {
                    "day $day of $daysInMonth · ${maskedAmount(formatCurrency(-delta), hidden)} behind pace"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProportionBar(
    fraction: Double,
    height: androidx.compose.ui.unit.Dp,
    track: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(fill),
        )
    }
}

/**
 * How the headline relates to what was actually printed on the receipts. Stated
 * rather than hidden: items + tax should land on the receipt total, and when it
 * doesn't, the gap gets its own named row and a sentence saying what it usually is
 * — a scan that missed a discount line will otherwise look like arithmetic the app
 * got wrong.
 */
@Composable
private fun ReconciliationCard(summary: SpendSummary.Month, hidden: Boolean) {
    BbCard {
        FooterRow("Items", maskedAmount(formatCurrency(summary.itemsTotal), hidden))
        if (summary.tax > 0) {
            FooterRow("Tax", maskedAmount(formatCurrency(summary.tax), hidden))
        }
        FooterRow("Receipt total", maskedAmount(formatCurrency(summary.receiptTotal), hidden))
        summary.unaccounted?.let { gap ->
            FooterRow("Unaccounted", maskedAmount(formatCurrency(gap), hidden))
            Text(
                "Items and tax don't add up to what the receipts say — usually a discount or a " +
                    "line the scan didn't read.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (summary.excludedCount > 0) {
            val n = summary.excludedCount
            FooterRow("Excluded", "$n receipt${if (n == 1) "" else "s"}")
        }
        if (summary.unreadablePriceCount > 0) {
            FooterRow("Unreadable prices", "${summary.unreadablePriceCount}")
        }
    }
}

@Composable
private fun FooterRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Editor for the monthly target — the one budget thing this screen itself changes;
 * which root the target applies to stays a Settings concern, same store, so the
 * two can't drift.
 */
@Composable
private fun BudgetAmountDialog(
    initial: Double?,
    rootLabel: String,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.let { "%.2f".format(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly Budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A monthly target for tracked $rootLabel spend, computed from your scanned " +
                        "receipts' items. Leave blank to track spend with no target.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.toDoubleOrNull()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
