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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
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
import com.zhenbo.beanbeaver.ui.theme.cardBackground
import com.zhenbo.beanbeaver.ui.theme.groupedBackground

/**
 * Every scanned receipt — the "see everything I scanned" and "bulk backup" halves
 * of the feature. Kotlin twin of iOS `ReceiptsView`.
 *
 * Unlike a batch, this list never drains on export: a receipt lives here until the
 * user deletes it or clears its photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    /**
     * When set (from the spending screen), only that month's receipts are shown
     * and the title reflects it. Null shows everything, newest first.
     */
    monthFilter: String?,
    onOpenReceipt: (SpendRecord) -> Unit,
    onExport: (List<SpendRecord>) -> Unit,
    exportReady: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    val allRecords by SpendStore.records.collectAsStateWithLifecycle()
    val records = remember(allRecords, monthFilter) {
        if (monthFilter == null) allRecords
        else allRecords.filter { SpendSummary.monthId(it) == monthFilter }
    }

    var editing by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    var confirmClearAllPhotos by rememberSaveable { mutableStateOf(false) }

    val selected = records.filter { it.id in selection }

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(monthFilter?.let(SpendSummary::monthLabel) ?: "Receipts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (records.isEmpty()) return@TopAppBar
                    TextButton(onClick = {
                        editing = !editing
                        if (!editing) selection = emptySet()
                    }) { Text(if (editing) "Done" else "Select") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Select Unexported") },
                                onClick = {
                                    menuOpen = false
                                    selection = records.filterNot { it.isExported }.map { it.id }.toSet()
                                    editing = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All Photos") },
                                onClick = { menuOpen = false; confirmClearAllPhotos = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete All Receipts") },
                                onClick = { menuOpen = false; confirmDeleteAll = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No Receipts", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (monthFilter == null) "Scanned receipts show up here."
                    else "No receipts scanned this month.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    ReceiptRow(
                        record = record,
                        caption = caption(context, record),
                        editing = editing,
                        checked = record.id in selection,
                        onToggle = {
                            selection = if (record.id in selection) selection - record.id
                            else selection + record.id
                        },
                        onOpen = { onOpenReceipt(record) },
                        onToggleExcluded = {
                            SpendStore.setExcluded(context, record.id, !record.isExcluded)
                        },
                        onDelete = { SpendStore.remove(context, record.id) },
                    )
                }
            }

            if (editing) {
                EditFooter(
                    selectedCount = selected.size,
                    exportReady = exportReady,
                    onDelete = { confirmDeleteSelected = true },
                    onExport = { onExport(selected) },
                )
            }
        }
    }

    if (confirmClearAllPhotos) {
        ConfirmDialog(
            title = "Clear all photos?",
            message = "Frees the space used by every receipt photo. Every receipt's parsed data " +
                "and every spend figure stay exactly as they are.",
            confirmLabel = "Clear Photos",
            onConfirm = { SpendStore.clearAllPhotos(context); confirmClearAllPhotos = false },
            onDismiss = { confirmClearAllPhotos = false },
        )
    }
    if (confirmDeleteSelected) {
        val n = selected.size
        ConfirmDialog(
            title = if (n == 1) "Delete this receipt?" else "Delete $n receipts?",
            message = "Removes the parsed data and the photos for the receipts you selected. " +
                "Everything else stays. Anything already exported to your ledger is untouched, " +
                "and originals stay in your photo library.",
            confirmLabel = "Delete $n Receipt${if (n == 1) "" else "s"}",
            onConfirm = {
                SpendStore.remove(context, selection)
                selection = emptySet()
                confirmDeleteSelected = false
                // Leave the user somewhere sensible: the toolbar is hidden for an
                // empty list, so staying in edit mode would take "Done" with it
                // and strand the screen.
                if (records.size == n) editing = false
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
    if (confirmDeleteAll) {
        val n = allRecords.size
        ConfirmDialog(
            title = "Delete all receipts?",
            message = "Removes the parsed data and the photos for every scanned receipt on this " +
                "device. Anything already exported to your ledger is untouched, and originals " +
                "stay in your photo library.",
            confirmLabel = "Delete $n Receipt${if (n == 1) "" else "s"}",
            onConfirm = {
                SpendStore.removeAll(context)
                selection = emptySet()
                editing = false
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

/**
 * The row's export/photo/excluded state — most receipts most of the time carrying
 * none of it: an unexported, un-tidied, included receipt says nothing rather than
 * warning about a state that's simply normal.
 */
private fun caption(context: android.content.Context, record: SpendRecord): String? = buildList {
    record.exportedTargets.forEach {
        add(if (it == "Money Manager") "Shared to Money Manager" else "Filed to $it")
    }
    when (SpendStore.photoState(context, record)) {
        SpendRecord.PhotoState.PRESENT -> Unit
        SpendRecord.PhotoState.CLEARED -> add("Photo cleared")
        SpendRecord.PhotoState.UNAVAILABLE -> add("Photo unavailable")
    }
    if (record.isExcluded) add("Excluded from spend")
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun ReceiptRow(
    record: SpendRecord,
    caption: String?,
    editing: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onToggleExcluded: () -> Unit,
    onDelete: () -> Unit,
) {
    var rowMenuOpen by remember { mutableStateOf(false) }
    val result = record.result

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBackground,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (editing) onToggle() else onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        titleCase(result.merchant),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (result.needsAttention) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Needs a look",
                            tint = BbAccent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                val subtitle = buildList {
                    friendlyDate(result.date)?.let { add(it) }
                    val count = result.items.size
                    if (count > 0) add("$count item${if (count == 1) "" else "s"}")
                }.joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                formatPrice(result.total).text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            if (!editing) {
                // Compose has no swipe actions the way a SwiftUI List does, so the
                // per-row destructive/exclude gestures live in an overflow menu.
                Box {
                    IconButton(onClick = { rowMenuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Actions for ${titleCase(result.merchant)}",
                        )
                    }
                    DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (record.isExcluded) "Include in spend" else "Exclude from spend") },
                            onClick = { rowMenuOpen = false; onToggleExcluded() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { rowMenuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Both things a selection can be used for, side by side. Deleting a chosen few
 * used to have no home here: the row menu does one and the overflow does all of
 * them, so trimming a dozen receipts meant a dozen taps. Selection already existed
 * for export — this just lets the same selection be thrown away, which is also why
 * "Select Unexported" now composes into "delete what I never filed".
 */
@Composable
private fun EditFooter(
    selectedCount: Int,
    exportReady: Boolean,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = cardBackground, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (selectedCount == 1) "Delete 1 selected receipt"
                    else "Delete $selectedCount selected receipts",
                    tint = BbAccent,
                )
            }
            Button(
                onClick = onExport,
                enabled = selectedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        !exportReady -> "Set Up Export…"
                        selectedCount == 1 -> "Export 1 Receipt"
                        else -> "Export $selectedCount Receipts"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * One recorded receipt in full, reached from the Receipts list or from a category
 * drill-down. The batch's detail screen can't be reused as-is: its destructive
 * action is "remove from batch", while here the photo and the record have separate
 * lifetimes — clearing the photo keeps every spend figure the record contributes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordedReceiptScreen(record: SpendRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    var showPhoto by rememberSaveable { mutableStateOf(false) }
    var confirmClearPhoto by rememberSaveable { mutableStateOf(false) }

    // Re-read from the store rather than trusting the record handed in, so
    // clearing the photo updates this screen instead of only the list behind it.
    val records by SpendStore.records.collectAsStateWithLifecycle()
    val current = records.firstOrNull { it.id == record.id } ?: record
    val photoFile = SpendStore.photoFile(context, current)

    val photoBytes by androidx.compose.runtime.produceState<ByteArray?>(null, photoFile?.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            photoFile?.let { f -> runCatching { f.readBytes() }.getOrNull() }
        }
    }

    if (showPhoto) {
        photoBytes?.let {
            OriginReceiptScreen(imageData = it, onBack = { showPhoto = false })
            return
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(titleCase(current.result.merchant), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showPhoto = true }, enabled = photoBytes != null) {
                        Icon(Icons.Default.Photo, contentDescription = "Show original receipt")
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
            ReceiptCard(result = current.result, wallMs = current.wallMs ?: 0.0)

            when (SpendStore.photoState(context, current)) {
                SpendRecord.PhotoState.PRESENT -> {
                    OutlinedButton(
                        onClick = { confirmClearPhoto = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Photo", fontWeight = FontWeight.SemiBold)
                    }
                }
                SpendRecord.PhotoState.CLEARED -> PhotoNote("You cleared this receipt's photo.")
                SpendRecord.PhotoState.UNAVAILABLE ->
                    // Worth naming: a re-export of this row can attach no
                    // `document:` link, which is not the user's doing.
                    PhotoNote("This receipt's photo is no longer on this device.")
            }
        }
    }

    if (confirmClearPhoto) {
        ConfirmDialog(
            title = "Clear this photo?",
            message = "Frees the space used by this receipt's photo. Its parsed data and every " +
                "spend figure it contributes to stay exactly as they are.",
            confirmLabel = "Clear Photo",
            onConfirm = { SpendStore.clearPhoto(context, current.id); confirmClearPhoto = false },
            onDismiss = { confirmClearPhoto = false },
        )
    }
}

@Composable
private fun PhotoNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
