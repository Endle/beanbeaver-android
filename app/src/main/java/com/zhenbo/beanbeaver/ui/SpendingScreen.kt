package com.zhenbo.beanbeaver.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.AmountPrivacy
import com.zhenbo.beanbeaver.receipt.BudgetPrefs
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import java.time.LocalDate
import java.time.YearMonth

/**
 * Where a month's money went, computed from scanned receipts' *items* rather
 * than their totals (see `SpendSummary`). A spend tracker first: the headline is
 * everything tracked, and the breakdown is every category the classifier
 * reached, largest first. A monthly target is an optional overlay — when
 * `BudgetPrefs.monthlyAmount` is unset, nothing budget-shaped renders at all and
 * the screen is complete without it. Kotlin twin of iOS `SpendingView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(
    onScan: () -> Unit,
    onOpenReceipts: (String?) -> Unit,
    /** (category, title, the month the tap happened in) — the drill-down is scoped
     *  to the month on screen, so the list can't silently change what the number
     *  referred to. */
    onOpenCategory: (SpendSummary.Category, String, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    SpendStore.ensureLoaded(context)
    val records by SpendStore.records.collectAsStateWithLifecycle()

    var selectedMonthID by rememberSaveable { mutableStateOf<String?>(null) }
    var hideAmounts by rememberSaveable { mutableStateOf(AmountPrivacy.hideAmounts(context)) }
    var monthlyAmount by remember { mutableStateOf(BudgetPrefs.monthlyAmount(context)) }
    var showBudgetSheet by remember { mutableStateOf(false) }

    val monthIDs = SpendSummary.monthIds(from = records)
    val activeMonthID = selectedMonthID ?: SpendSummary.defaultMonthId(from = records)
    val isCurrentMonth = activeMonthID == SpendSummary.currentMonthId()
    val summary = SpendSummary.month(activeMonthID, from = records)
    val targetRoot = BudgetPrefs.root(context)

    fun goOlder() {
        val idx = monthIDs.indexOf(activeMonthID)
        if (idx >= 0 && idx + 1 < monthIDs.size) selectedMonthID = monthIDs[idx + 1]
    }

    fun goNewer() {
        val idx = monthIDs.indexOf(activeMonthID)
        if (idx > 0) selectedMonthID = monthIDs[idx - 1]
    }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Spending") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = {
                            hideAmounts = !hideAmounts
                            AmountPrivacy.setHideAmounts(context, hideAmounts)
                        }) {
                            Icon(
                                if (hideAmounts) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (hideAmounts) "Show Amounts" else "Hide Amounts",
                            )
                        }
                        IconButton(onClick = { onOpenReceipts(null) }) {
                            Icon(Icons.Outlined.ReceiptLong, contentDescription = "All Receipts")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            EmptySpendState(modifier = Modifier.padding(padding), onScan = onScan)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MonthStepper(
                        label = summary.label,
                        canGoOlder = monthIDs.indexOf(activeMonthID) < monthIDs.lastIndex,
                        canGoNewer = monthIDs.indexOf(activeMonthID) > 0,
                        onOlder = ::goOlder,
                        onNewer = ::goNewer,
                    )
                    Headline(
                        amount = summary.tracked,
                        masked = hideAmounts,
                        receiptCount = summary.records.size,
                        onOpenReceipts = { onOpenReceipts(activeMonthID) },
                    )
                }

                summary.roots.forEach { group ->
                    RootCard(
                        group = group,
                        maxLeaf = summary.maxLeafAmount,
                        masked = hideAmounts,
                        targetRoot = targetRoot,
                        monthlyAmount = monthlyAmount,
                        isCurrentMonth = isCurrentMonth,
                        onOpen = { category, title -> onOpenCategory(category, title, activeMonthID) },
                        onEditBudget = { showBudgetSheet = true },
                    )
                }

                FooterSection(summary, masked = hideAmounts, modifier = Modifier.padding(horizontal = 16.dp))

                if (monthlyAmount == null) {
                    SetBudgetRow(
                        onClick = { showBudgetSheet = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showBudgetSheet) {
        BudgetAmountDialog(
            initial = monthlyAmount,
            rootLabel = targetRoot.replaceFirstChar { it.uppercase() },
            onDismiss = {
                showBudgetSheet = false
                monthlyAmount = BudgetPrefs.monthlyAmount(context)
            },
            onSave = { amount ->
                BudgetPrefs.setMonthlyAmount(context, amount)
                monthlyAmount = amount
                showBudgetSheet = false
            },
        )
    }
}

@Composable
private fun EmptySpendState(modifier: Modifier, onScan: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.ReceiptLong,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text("Nothing Tracked Yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            "Scan a receipt and its items show up here, sorted into categories.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onScan) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Scan a Receipt", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MonthStepper(label: String, canGoOlder: Boolean, canGoNewer: Boolean, onOlder: () -> Unit, onNewer: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOlder, enabled = canGoOlder) { Text("<") }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onNewer, enabled = canGoNewer) { Text(">") }
    }
}

@Composable
private fun Headline(amount: Double, masked: Boolean, receiptCount: Int, onOpenReceipts: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(amount),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = BbAccent,
        )
        Text("tracked spend", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "$receiptCount receipt${if (receiptCount == 1) "" else "s"}  ›",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenReceipts)
                .padding(4.dp),
        )
    }
}

@Composable
private fun RootCard(
    group: SpendSummary.RootGroup,
    maxLeaf: Double,
    masked: Boolean,
    targetRoot: String,
    monthlyAmount: Double?,
    isCurrentMonth: Boolean,
    onOpen: (SpendSummary.Category, String) -> Unit,
    onEditBudget: () -> Unit,
) {
    val hasTarget = group.id == targetRoot && monthlyAmount != null
    BbCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(SpendSummary.Category.Root(group.id), group.label) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(group.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("  ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (hasTarget && monthlyAmount != null) {
            TargetBar(spent = group.amount, target = monthlyAmount, masked = masked, isCurrentMonth = isCurrentMonth, onEdit = onEditBudget)
        }

        group.leaves.forEach { leaf ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(SpendSummary.Category.Leaf(leaf.label), leaf.label) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    leaf.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(leaf.amount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text("  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (maxLeaf > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BbAccentSoft),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((leaf.amount / maxLeaf).toFloat().coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(BbAccent),
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetBar(spent: Double, target: Double, masked: Boolean, isCurrentMonth: Boolean, onEdit: () -> Unit) {
    val remaining = target - spent
    val fraction = if (target > 0) spent / target else 0.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (fraction > 1) MaterialTheme.colorScheme.error else BbAccent),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (remaining >= 0) {
                    "${if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(remaining)} left"
                } else {
                    "${if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(-remaining)} over"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEdit) {
                Text("of ${if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(target)}")
            }
        }
        if (isCurrentMonth) {
            PaceLine(spent = spent, target = target, masked = masked)
        }
    }
}

/** Spend-to-date against day-of-month — what makes a target actionable rather
 *  than retrospective. Current month only; for a past month the month's own total
 *  is the whole answer. */
@Composable
private fun PaceLine(spent: Double, target: Double, masked: Boolean) {
    val today = LocalDate.now()
    val daysInMonth = YearMonth.from(today).lengthOfMonth()
    val expectedByNow = target * today.dayOfMonth / daysInMonth
    val delta = expectedByNow - spent
    val text = if (delta >= 0) {
        "day ${today.dayOfMonth} of $daysInMonth · ${if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(delta)} ahead of pace"
    } else {
        "day ${today.dayOfMonth} of $daysInMonth · ${if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(-delta)} behind pace"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** How the headline relates to what was actually printed on the receipts. Stated
 *  rather than hidden: `items + tax` should land on `receiptTotal`, and when it
 *  doesn't, the gap gets its own named row and a sentence saying what it usually
 *  is. */
@Composable
private fun FooterSection(summary: SpendSummary.Month, masked: Boolean, modifier: Modifier = Modifier) {
    BbCard(modifier = modifier) {
        FooterRow("Items", if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(summary.itemsTotal))
        if (summary.tax > 0) {
            FooterRow("Tax", if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(summary.tax))
        }
        FooterRow("Receipt total", if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(summary.receiptTotal))
        val gap = summary.unaccounted
        if (gap != null) {
            FooterRow("Unaccounted", if (masked) AmountPrivacy.PLACEHOLDER else priceCurrency(gap))
            Text(
                "Items and tax don't add up to what the receipts say — usually a discount or a line the scan didn't read.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.excludedCount > 0) {
            FooterRow("Excluded", "${summary.excludedCount} receipt${if (summary.excludedCount == 1) "" else "s"}")
        }
        if (summary.unreadablePriceCount > 0) {
            FooterRow("Unreadable prices", summary.unreadablePriceCount.toString())
        }
    }
}

@Composable
private fun FooterRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Offered once, quietly, at the bottom — a target is opt-in and the screen is
 *  complete without one. */
@Composable
private fun SetBudgetRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BbCard(modifier = modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Set a Monthly Budget",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount") },
                    singleLine = true,
                )
                Text(
                    "A monthly target for tracked $rootLabel spend, computed from your scanned " +
                        "receipts' items. Leave blank to track spend with no target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.toDoubleOrNull()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
