package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.needsAttention
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.groupedBackground

/**
 * What one category total is made of — the receipts behind it, each showing only
 * the items that landed in this category, with the full receipt one tap further
 * on. Kotlin twin of iOS `CategoryItemsView`.
 *
 * This is the middle rung of the drill-down: a month's total asks "where did it
 * go", a category asks "which items", and only then does a receipt answer "what
 * did that purchase look like".
 *
 * Grouped by receipt rather than listed flat because a category total is spread
 * over *purchases* — "$8.42 of this Costco run was dairy" is the shape of the
 * answer, and a flat list buries it by repeating the merchant on every row. The
 * grouping never answers with a whole-receipt total, which would be a figure
 * unrelated to the one tapped: each receipt leads with its **share**, and the
 * receipt's own total sits in the caption as context.
 *
 * Scoped to the month it was reached from — an unscoped list would quietly change
 * what the number on the previous screen referred to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryItemsScreen(
    category: SpendSummary.Category,
    /**
     * Shown as the title. Passed in rather than derived, because a root's display
     * wording lives on `RootGroup.label` while the category itself is selected by
     * raw tag id — see [SpendSummary.Category].
     */
    title: String,
    monthId: String,
    onOpenReceipt: (SpendRecord) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val hidden by AmountPrivacy.hideAmounts.collectAsStateWithLifecycle()
    val records by SpendStore.records.collectAsStateWithLifecycle()

    val groups = remember(records, category, monthId) {
        SpendSummary.receipts(category, records.filter { SpendSummary.monthId(it) == monthId })
    }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No Items", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nothing in this category for ${SpendSummary.monthLabel(monthId)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
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
            // The figure that was tapped, restated with the month it came from:
            // arriving here should confirm the number, not leave it to be
            // re-added by eye.
            val itemCount = groups.sumOf { it.entries.size }
            val total = groups.sumOf { it.amount }
            BbCard {
                Text(
                    SpendSummary.monthLabel(monthId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$itemCount item${if (itemCount == 1) "" else "s"} in " +
                            "${groups.size} receipt${if (groups.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        maskedAmount(formatCurrency(total), hidden),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            groups.forEach { group ->
                BbCard {
                    ReceiptShareHeader(
                        group = group,
                        hidden = hidden,
                        onClick = { onOpenReceipt(group.record) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                    group.entries.forEach { ItemRow(it, hidden) }
                }
            }
        }
    }
}

/**
 * One receipt's share of the category. The share leads — it's what the previous
 * screen's figure is made of — while the receipt's own total is context in the
 * caption, per this file's header.
 */
@Composable
private fun ReceiptShareHeader(
    group: SpendSummary.ReceiptGroup,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    val result = group.record.result
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                titleCase(result.merchant),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            // Same badge ParsedRow uses, so a receipt worth a second look is
            // flagged identically wherever it's listed.
            if (result.needsAttention) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = "Needs a look",
                    tint = BbAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                maskedAmount(formatCurrency(group.amount), hidden),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            subtitle(group, hidden),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Date · how many of the receipt's items landed here · what the whole receipt came
 * to. Each clause is dropped rather than faked when its source didn't parse — an
 * unreadable total says nothing instead of "$0.00".
 */
private fun subtitle(group: SpendSummary.ReceiptGroup, hidden: Boolean): String = buildList {
    friendlyDate(group.record.result.date)?.let { add(it) }
    val onReceipt = group.record.result.items.size
    add("${group.entries.size} of $onReceipt item${if (onReceipt == 1) "" else "s"}")
    group.receiptTotal?.let { add("${maskedAmount(formatCurrency(it), hidden)} total") }
}.joinToString(" · ")

/**
 * An item under its receipt. No merchant or date here — the header says both once,
 * which is the point of grouping.
 */
@Composable
private fun ItemRow(entry: SpendSummary.ItemEntry, hidden: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // `×quantity` in the label, price left as the line amount — the convention
        // MoneyManagerExport.row already follows, so multi-quantity lines read the
        // same in both places.
        Text(
            if (entry.item.quantity > 1) {
                "${entry.item.description} ×${entry.item.quantity}"
            } else {
                entry.item.description
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            maskedAmount(formatPrice(entry.item.price).text, hidden),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
