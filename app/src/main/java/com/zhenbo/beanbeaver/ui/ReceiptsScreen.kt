package com.zhenbo.beanbeaver.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenbo.beanbeaver.Entitlements
import com.zhenbo.beanbeaver.debug.DebugInfoStore
import com.zhenbo.beanbeaver.export.MoneyManagerExport
import com.zhenbo.beanbeaver.github.LedgerEntry
import com.zhenbo.beanbeaver.receipt.SpendRecord
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.needsAttention
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.cardBackground
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every scanned receipt — the "see everything I scanned" and "bulk backup"
 * halves of the feature. Unlike a batch this list never drains on export: a
 * receipt lives here until the user deletes it or clears its photo. Kotlin twin
 * of iOS `ReceiptsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    /** When set (from `SpendingScreen`), only that month's receipts are shown
     *  and the title reflects it. Null shows everything, newest first. */
    monthFilter: String?,
    githubConfigured: Boolean,
    exportRunning: Boolean,
    exportMessage: String?,
    onExportEntries: (List<LedgerEntry>) -> Unit,
    onConfigureExport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    SpendStore.ensureLoaded(context)
    val allRecords by SpendStore.records.collectAsStateWithLifecycle()
    val records = if (monthFilter == null) allRecords else allRecords.filter {
        SpendSummary.monthId(for = it) == monthFilter
    }

    var editing by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var confirmClearAllPhotos by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedRecords = records.filter { it.id in selection }

    val detailRecord = allRecords.firstOrNull { it.id == detailId }
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
                title = {
                    Text(
                        monthFilter?.let { SpendSummary.monthLabel(for = it) } ?: "Receipts",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        TextButton(onClick = {
                            editing = !editing
                            if (!editing) selection = emptySet()
                        }) { Text(if (editing) "Done" else "Select") }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Select Unexported") },
                                leadingIcon = {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    selection = records.filterNot { it.isExported }.map { it.id }.toSet()
                                    editing = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear All Photos") },
                                leadingIcon = { Icon(Icons.Default.Photo, contentDescription = null) },
                                onClick = { menuOpen = false; confirmClearAllPhotos = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete All Receipts") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDeleteAll = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (records.isNotEmpty() && editing) {
                EditFooter(
                    selectedCount = selectedRecords.size,
                    exportRunning = exportRunning,
                    exportMessage = exportMessage,
                    githubConfigured = githubConfigured,
                    onDelete = { confirmDeleteSelected = true },
                    onExport = {
                        exportSelected(
                            context,
                            selectedRecords,
                            githubConfigured,
                            onExportEntries,
                            onConfigureExport,
                        )
                    },
                )
            }
        },
    ) { padding ->
        if (records.isEmpty()) {
            EmptyReceipts(monthFilter = monthFilter, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    SpendRow(
                        record = record,
                        editing = editing,
                        selected = record.id in selection,
                        onToggle = {
                            selection = if (record.id in selection) selection - record.id else selection + record.id
                        },
                        onClick = {
                            if (editing) {
                                selection = if (record.id in selection) selection - record.id else selection + record.id
                            } else {
                                detailId = record.id
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all receipts?") },
            text = {
                Text(
                    "Removes the parsed data and the photos for every scanned receipt on this device. " +
                        "Anything already exported to your ledger is untouched, and originals stay in " +
                        "your photo library.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    SpendStore.removeAll(context)
                    selection = emptySet()
                    editing = false
                }) { Text("Delete ${allRecords.size} Receipt${if (allRecords.size == 1) "" else "s"}") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteSelected) {
        val count = selectedRecords.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text(if (count == 1) "Delete this receipt?" else "Delete $count receipts?") },
            text = {
                Text(
                    "Removes the parsed data and the photos for the receipts you selected. " +
                        "Anything already exported to your ledger is untouched, and originals stay in " +
                        "your photo library.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    SpendStore.remove(context, selection)
                    selection = emptySet()
                    if (records.isEmpty()) editing = false
                }) {
                    Text("Delete $count Receipt${if (count == 1) "" else "s"}")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }
    if (confirmClearAllPhotos) {
        AlertDialog(
            onDismissRequest = { confirmClearAllPhotos = false },
            title = { Text("Clear all photos?") },
            text = {
                Text(
                    "Frees the space used by every receipt photo. Every receipt's parsed data and every " +
                        "spend figure stay exactly as they are.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAllPhotos = false
                    SpendStore.clearAllPhotos(context)
                }) { Text("Clear Photos") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAllPhotos = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyReceipts(monthFilter: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Photo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            if (monthFilter == null) "No Receipts" else "No receipts this month",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (monthFilter == null) "Scanned receipts show up here." else "Nothing scanned into this month yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpendRow(record: SpendRecord, editing: Boolean, selected: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val caption = spendCaption(context, record)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) BbAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onToggle),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        titleCase(record.result.merchant),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (record.result.needsAttention) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Needs a look",
                            tint = BbAccent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                val subtitle = buildList {
                    friendlyDate(record.result.date)?.let { add(it) }
                    val count = record.result.items.size
                    if (count > 0) add("$count item${if (count == 1) "" else "s"}")
                }.joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!caption.isNullOrEmpty()) {
                    Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                formatPrice(record.result.total).text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The row's export/photo/excluded state, most receipts most of the time carrying
 *  none of it: an unexported, un-tidied, included receipt says nothing rather
 *  than warning about a state that's simply normal. */
@Composable
private fun spendCaption(context: Context, record: SpendRecord): String? {
    val parts = mutableListOf<String>()
    for (target in record.exportedTargets) {
        parts += if (target == "Money Manager") "Shared to Money Manager" else "Filed to $target"
    }
    when (SpendStore.photoState(context, record)) {
        SpendRecord.PhotoState.PRESENT -> {}
        SpendRecord.PhotoState.CLEARED -> parts += "Photo cleared"
        SpendRecord.PhotoState.UNAVAILABLE -> parts += "Photo unavailable"
    }
    if (record.isExcluded) parts += "Excluded from budgets"
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun EditFooter(
    selectedCount: Int,
    exportRunning: Boolean,
    exportMessage: String?,
    githubConfigured: Boolean,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(cardBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onDelete,
                enabled = selectedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete", fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onExport,
                enabled = selectedCount > 0 && !exportRunning,
                modifier = Modifier.weight(1f),
            ) {
                if (exportRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(exportMessage ?: "Exporting…", maxLines = 1)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !githubConfigured -> "Set Up Export"
                            selectedCount == 1 -> "Export 1"
                            else -> "Export $selectedCount"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Sends the selected receipts to the selected target. Never drains the list
 *  either way — these receipts stay put whether they're being filed for the
 *  first time or re-filed. A ledger export marks itself via the one hook in
 *  `GitHubSyncViewModel.export`; Money Manager is marked here, at presentation,
 *  since the share sheet that follows may be cancelled. */
private fun exportSelected(
    context: Context,
    selected: List<SpendRecord>,
    githubConfigured: Boolean,
    onExportEntries: (List<LedgerEntry>) -> Unit,
    onConfigureExport: () -> Unit,
) {
    if (selected.isEmpty()) return
    if (githubConfigured) {
        val entries = selected.map {
            LedgerEntry.make(context, it.result, SpendStore.photoBytes(context, it), it.wallMs)
        }
        onExportEntries(entries)
    } else if (Entitlements.isPremium(context)) {
        val results = selected.map { it.result }
        runCatching { MoneyManagerExport.makeFile(context, results) }
            .onSuccess { file ->
                com.zhenbo.beanbeaver.export.ShareFile.share(
                    context, file, com.zhenbo.beanbeaver.export.ShareFile.XLSX_MIME, "Export to Money Manager")
            }
            .onFailure {
                DebugInfoStore.recordExportFailure(context, "export to Money Manager", it.message ?: it.toString())
                Toast.makeText(context, "Couldn't build the spreadsheet.", Toast.LENGTH_LONG).show()
            }
        SpendStore.markShared(context, results)
    } else {
        onConfigureExport()
    }
}

/**
 * One receipt's full view from the Receipts list: the parsed card, the original
 * photo, and — since the photo belongs to a settled `SpendRecord` here — the
 * option to clear it. Kotlin twin of the ReceiptsView → `BatchReceiptDetailView`
 * path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptDetailScreen(
    record: SpendRecord,
    onClearPhoto: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showPhoto by rememberSaveable { mutableStateOf(false) }

    val photoBytes by produceState<ByteArray?>(null, record.id) {
        value = withContext(Dispatchers.IO) { SpendStore.photoBytes(context, record) }
    }

    if (showPhoto) {
        photoBytes?.let {
            OriginReceiptScreen(imageData = it, onBack = { showPhoto = false })
            return
        }
    }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(titleCase(record.result.merchant), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (photoBytes != null) {
                        IconButton(onClick = {
                            if (photoBytes != null) showPhoto = true
                        }) {
                            Icon(Icons.Default.Photo, contentDescription = "Show original receipt")
                        }
                    }
                    if (photoBytes != null) {
                        IconButton(onClick = {
                            onClearPhoto()
                            onBack()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear photo")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReceiptCard(result = record.result, wallMs = record.wallMs ?: 0.0)
        }
    }
}
