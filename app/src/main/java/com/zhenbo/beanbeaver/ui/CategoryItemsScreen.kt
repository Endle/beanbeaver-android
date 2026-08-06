package com.zhenbo.beanbeaver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.groupedBackground

/**
 * What one category total is made of — the receipts behind it, each showing only
 * the items that landed in this category, with the full receipt one tap further
 * on. This is the middle rung of the drill-down: a month's total asks "where did
 * it go", a category asks "which items", and only then does a receipt answer
 * "what did that purchase look like". Kotlin twin of iOS `CategoryItemsView`.
 *
 * Grouped by receipt rather than listed flat because a category total is spread
 * over *purchases* — "$8.42 of this Costco run was dairy" is the shape of the
 * answer. Each receipt leads with its **share**, and the receipt's own total sits
 * in the caption as context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryItemsScreen(
    category: SpendSummary.Category,
    /** Shown as the title. Passed in rather than derived, because a root's display
     *  wording lives on `RootGroup.label` while the category itself is selected by
     *  raw tag id — see `SpendSummary.Category`. */
    title: String,
    monthID: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    SpendStore.ensureLoaded(context)
    val records by SpendStore.records.collectAsStateWithLifecycle()
    val groups = SpendSummary.receipts(category, from = records.filter { SpendSummary.monthId(for = it) == monthID })

    var detailId by rememberSaveable { mutableStateOf<String?>(null) }

    val detailRecord = groups.firstOrNull { it.record.id == detailId }?.record
    if (detailRecord != null) {
        ReceiptDetailScreen(
            record = detailRecord,
            onClearPhoto = { SpendStore.clearPhoto(context, detailRecord.id) },
            onBack = { detailId = null },
        )
        return
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (groups.isEmpty()) {
                item {
                    Text(
                        "Nothing in this category for ${SpendSummary.monthLabel(for = monthID)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val itemCount = groups.sumOf { it.entries.size }
                val total = groups.sumOf { it.amount }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            SpendSummary.monthLabel(for = monthID),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            Text(
                                "$itemCount item${if (itemCount == 1) "" else "s"} in " +
                                    "${groups.size} receipt${if (groups.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                priceCurrency(total),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                groups.forEach { group ->
                    item {
                        ReceiptGroupHeader(group = group, onClick = { detailId = group.record.id })
                    }
                    items(group.entries, key = { it.id }) { entry ->
                        ItemRow(entry)
                    }
                }
            }
        }
    }
}

/** One receipt's share of the category. The share leads — it's what the previous
 *  screen's figure is made of — while the receipt's own total is context in the
 *  caption. */
@Composable
private fun ReceiptGroupHeader(group: SpendSummary.ReceiptGroup, onClick: () -> Unit) {
    val result = group.record.result
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                titleCase(result.merchant),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (result.needsAttention) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = "Needs a look",
                    tint = BbAccent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                priceCurrency(group.amount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            subtitle(group),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Date · how many of the receipt's items landed here · what the whole receipt
 *  came to. Each clause is dropped rather than faked when its source didn't
 *  parse. */
private fun subtitle(group: SpendSummary.ReceiptGroup): String {
    val parts = mutableListOf<String>()
    friendlyDate(group.record.result.date)?.let { parts.add(it) }
    val onReceipt = group.record.result.items.size
    parts.add("${group.entries.size} of $onReceipt item${if (onReceipt == 1) "" else "s"}")
    group.receiptTotal?.let { parts.add("${priceCurrency(it)} total") }
    return parts.joinToString(" · ")
}

/** An item under its receipt. No merchant or date here — the section header says
 *  both once, which is the point of grouping. */
@Composable
private fun ItemRow(entry: SpendSummary.ItemEntry) {
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.item.quantity > 1) "${entry.item.description} ×${entry.item.quantity}"
            else entry.item.description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatPrice(entry.item.price).text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
