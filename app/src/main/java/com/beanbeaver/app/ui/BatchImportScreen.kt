package com.beanbeaver.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beanbeaver.app.github.LedgerEntry
import com.beanbeaver.app.receipt.DraftState
import com.beanbeaver.app.receipt.ReceiptBatch
import com.beanbeaver.app.receipt.ReceiptCaptureStore
import com.beanbeaver.app.receipt.ReceiptDraft
import com.beanbeaver.app.receipt.needsAttention
import com.beanbeaver.app.ui.theme.BbAccent
import com.beanbeaver.app.ui.theme.groupedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bb_receipt_ffi.ReceiptResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The photo-library import workspace: add a pile of receipts, watch them parse,
 * look over what came back, then export the lot in one pull request. The Android
 * twin of iOS `BatchImportView`.
 *
 * Deliberately not the camera flow — "Scan a Receipt" stays a single fast path for
 * one receipt at the counter. This is the sit-down-and-process-a-backlog path,
 * which is why it's a place you navigate to and can come back to (the batch and its
 * photos survive relaunch) rather than a picker that fires once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchImportScreen(
    batch: ReceiptBatch,
    exportRunning: Boolean,
    exportMessage: String?,
    githubConfigured: Boolean,
    onExport: (List<LedgerEntry>) -> Unit,
    onConfigureGitHub: () -> Unit,
    onBack: () -> Unit,
) {
    val drafts by batch.drafts.collectAsStateWithLifecycle()
    val isParsing by batch.isParsing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var isLoadingPicked by remember { mutableStateOf(false) }
    var duplicatesSkipped by remember { mutableStateOf(0) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Anything queued or interrupted (e.g. left over from a previous visit or a
    // kill mid-scan) resumes here — these are receipts the user already asked for.
    LaunchedEffect(Unit) { batch.startParsing() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isLoadingPicked = true
            // One photo at a time: `add` writes each to disk and drops it, so a
            // twenty-image selection never sits in memory at once.
            val dupes = withContext(Dispatchers.IO) {
                var d = 0
                uris.forEach { uri ->
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && batch.add(bytes) == ReceiptBatch.AddOutcome.DUPLICATE) d++
                }
                d
            }
            duplicatesSkipped = dupes
            isLoadingPicked = false
            batch.startParsing()
        }
    }
    val launchPicker = {
        menuOpen = false
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Detail is a boolean-gated early return, matching how BeanBeaverApp stacks its
    // sub-screens. Backing out of it returns here.
    val detailDraft = drafts.firstOrNull { it.id == detailId }
    val detailParsed = detailDraft?.state as? DraftState.Parsed
    if (detailDraft != null && detailParsed != null) {
        BatchReceiptDetailScreen(
            result = detailParsed.result,
            wallMs = detailParsed.wallMs,
            captureFilename = detailDraft.captureFilename,
            onDelete = { detailId = null; batch.remove(detailDraft.id) },
            onBack = { detailId = null },
        )
        return
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Import from Photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (drafts.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Add Photos") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = launchPicker,
                            )
                            DropdownMenuItem(
                                text = { Text("Discard Batch") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDiscard = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (drafts.isNotEmpty()) {
                ExportFooter(
                    parsedCount = drafts.count { it.state is DraftState.Parsed },
                    remainingCount = drafts.count { it.state.needsParsing || it.state is DraftState.Scanning },
                    isParsing = isParsing,
                    exportRunning = exportRunning,
                    exportMessage = exportMessage,
                    githubConfigured = githubConfigured,
                    onStop = { batch.stopParsing() },
                    onExport = {
                        if (githubConfigured) onExport(batch.exportableEntries()) else onConfigureGitHub()
                    },
                )
            }
        },
    ) { padding ->
        if (drafts.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding), onAdd = launchPicker)
        } else {
            DraftList(
                modifier = Modifier.padding(padding),
                drafts = drafts,
                isLoadingPicked = isLoadingPicked,
                duplicatesSkipped = duplicatesSkipped,
                onOpen = { detailId = it },
                onRetry = { batch.retry(it) },
                onRemove = { batch.remove(it) },
            )
        }
    }

    if (confirmDiscard) {
        val count = drafts.size
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this batch?") },
            text = {
                Text(
                    "Removes every receipt waiting here, and its photo, from this device. " +
                        "Anything already exported to your ledger is untouched, and the originals " +
                        "stay in your photo library.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; batch.discardAll() }) {
                    Text("Discard ${count} Receipt${if (count == 1) "" else "s"}")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") }
            },
        )
    }
}

// MARK: - Empty state

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text("No receipts yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            "Add receipt photos from your library and BeanBeaver will read them all, " +
                "then file them to your ledger together.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add Photos", fontWeight = FontWeight.SemiBold)
        }
    }
}

// MARK: - List

@Composable
private fun DraftList(
    modifier: Modifier,
    drafts: List<ReceiptDraft>,
    isLoadingPicked: Boolean,
    duplicatesSkipped: Int,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            BatchHeader(drafts = drafts, isLoadingPicked = isLoadingPicked, duplicatesSkipped = duplicatesSkipped)
        }
        items(drafts, key = { it.id }) { draft ->
            when (val s = draft.state) {
                is DraftState.Parsed -> ParsedRow(s.result) { onOpen(draft.id) }
                is DraftState.Failed -> FailedRow(s.message, onRetry = { onRetry(draft.id) }, onRemove = { onRemove(draft.id) })
                is DraftState.Scanning -> PendingRow("Reading…", showsSpinner = true)
                is DraftState.Queued -> PendingRow("Waiting", showsSpinner = false)
                is DraftState.Interrupted -> PendingRow("Interrupted — will retry", showsSpinner = false)
            }
        }
    }
}

@Composable
private fun BatchHeader(drafts: List<ReceiptDraft>, isLoadingPicked: Boolean, duplicatesSkipped: Int) {
    val attention = drafts.count { (it.state as? DraftState.Parsed)?.result?.needsAttention == true }
    val createdAt = drafts.minOfOrNull { it.addedAt }
    val line = buildList {
        if (createdAt != null) add("Started " + SimpleDateFormat("MMM d", Locale.US).format(Date(createdAt)))
        add("${drafts.size} receipt${if (drafts.size == 1) "" else "s"}")
        if (attention > 0) add("$attention need${if (attention == 1) "s" else ""} a look")
    }.joinToString(" · ")

    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isLoadingPicked) {
            Text("Adding photos…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (duplicatesSkipped > 0) {
            Text(
                "$duplicatesSkipped already in this batch — skipped.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Rows

@Composable
private fun RowCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = com.beanbeaver.app.ui.theme.cardBackground,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun ParsedRow(result: ReceiptResult, onOpen: () -> Unit) {
    RowCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(titleCase(result.merchant), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (result.needsAttention) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = "Needs a look", tint = BbAccent, modifier = Modifier.size(16.dp))
                    }
                }
                val subtitle = rowSubtitle(result)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                formatPrice(result.total).text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun rowSubtitle(result: ReceiptResult): String = buildList {
    friendlyDate(result.date)?.let { add(it) }
    val count = result.items.size
    if (count > 0) add("$count item${if (count == 1) "" else "s"}")
}.joinToString(" · ")

@Composable
private fun PendingRow(label: String, showsSpinner: Boolean) {
    RowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showsSpinner) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FailedRow(message: String, onRetry: () -> Unit, onRemove: () -> Unit) {
    RowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = BbAccent, modifier = Modifier.size(16.dp))
                    Text("Couldn't read this one", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = BbAccent)
                }
                Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

// MARK: - Export footer

@Composable
private fun ExportFooter(
    parsedCount: Int,
    remainingCount: Int,
    isParsing: Boolean,
    exportRunning: Boolean,
    exportMessage: String?,
    githubConfigured: Boolean,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = com.beanbeaver.app.ui.theme.cardBackground) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isParsing) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Reading ($remainingCount left)", fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = onExport,
                enabled = !exportRunning && parsedCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exportRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(exportMessage ?: "Exporting…", maxLines = 1)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !githubConfigured -> "Set Up GitHub to Export"
                            parsedCount == 1 -> "Export 1 Receipt"
                            else -> "Export $parsedCount Receipts"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// MARK: - Detail

/**
 * One receipt out of a batch: the full [ReceiptCard], the original photo, and the
 * option to throw the parse away. Nothing exports from here — a batch goes to the
 * ledger as a unit. The Android twin of iOS `BatchReceiptDetailView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchReceiptDetailScreen(
    result: ReceiptResult,
    wallMs: Double?,
    captureFilename: String,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showPhoto by rememberSaveable { mutableStateOf(false) }

    val photoBytes by produceState<ByteArray?>(null, captureFilename) {
        value = withContext(Dispatchers.IO) {
            runCatching { ReceiptCaptureStore.file(context, captureFilename).readBytes() }.getOrNull()
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
                title = { Text(titleCase(result.merchant), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
            ReceiptCard(result = result, wallMs = wallMs ?: 0.0)
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Remove from batch", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
