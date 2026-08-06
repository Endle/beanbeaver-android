package com.zhenbo.beanbeaver.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhenbo.beanbeaver.Entitlements
import com.zhenbo.beanbeaver.debug.DebugInfoStore
import com.zhenbo.beanbeaver.export.MoneyManagerExport
import com.zhenbo.beanbeaver.export.ShareFile
import com.zhenbo.beanbeaver.github.GitHubSyncViewModel
import com.zhenbo.beanbeaver.github.LedgerEntry
import com.zhenbo.beanbeaver.receipt.PhotoSaver
import com.zhenbo.beanbeaver.receipt.ReceiptBatch
import com.zhenbo.beanbeaver.receipt.ReceiptPipeline
import com.zhenbo.beanbeaver.receipt.AmountPrivacy
import com.zhenbo.beanbeaver.receipt.ScanStatus
import com.zhenbo.beanbeaver.receipt.SpendStore
import com.zhenbo.beanbeaver.receipt.SpendSummary
import com.zhenbo.beanbeaver.receipt.label
import com.zhenbo.beanbeaver.receipt.totalMs
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.BbAccentSoft
import com.zhenbo.beanbeaver.ui.theme.groupedBackground
import uniffi.bb_receipt_ffi.MerchantMatchStatus
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.ScanTimings

/** The Receipts list opened from Spending carries the month it was reached from;
 *  null shows every receipt. */
private data class ReceiptsNav(val monthFilter: String?)

/** The category drill-down a Spending row opened — scoped to the month it was
 *  reached from, so the list can't silently change what the number referred to. */
private data class CategoryNav(
    val category: SpendSummary.Category,
    val title: String,
    val monthID: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeanBeaverApp(
    pipeline: ReceiptPipeline = viewModel(),
    githubVm: GitHubSyncViewModel = viewModel(),
    batch: ReceiptBatch = viewModel(),
) {
    val status by pipeline.status.collectAsStateWithLifecycle()
    val progress by pipeline.scanProgress.collectAsStateWithLifecycle()
    val stepLabel by pipeline.scanStepLabel.collectAsStateWithLifecycle()
    val capturedImage by pipeline.capturedImage.collectAsStateWithLifecycle()
    val skipOrientation by pipeline.skipOrientation.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val ghConfigured by githubVm.configured.collectAsStateWithLifecycle()
    val ghConnected by githubVm.connected.collectAsStateWithLifecycle()
    val ghAccount by githubVm.account.collectAsStateWithLifecycle()
    val exportRunning by githubVm.exportRunning.collectAsStateWithLifecycle()
    val exportMessage by githubVm.exportMessage.collectAsStateWithLifecycle()
    val exportResult by githubVm.exportResult.collectAsStateWithLifecycle()

    // Full-screen review of the original photo, opened from the result screen.
    var showOriginalReceipt by rememberSaveable { mutableStateOf(false) }
    // Read-only view of the .json sidecar an export would attach.
    var showJsonPreview by rememberSaveable { mutableStateOf(false) }
    // The Android twin of iOS Settings (sync, ledger prefs, scanning, debug, about).
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showGitHubSettings by rememberSaveable { mutableStateOf(false) }
    var showItemRules by rememberSaveable { mutableStateOf(false) }
    var showSpending by rememberSaveable { mutableStateOf(false) }
    var showReceipts by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showDataDump by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var showAcknowledgements by rememberSaveable { mutableStateOf(false) }
    // The photo-library batch workspace (multi-receipt import), a peer of the
    // single-scan flow rather than a step within it.
    var showBatch by rememberSaveable { mutableStateOf(false) }
    val image = capturedImage

    SpendStore.ensureLoaded(context)
    val spendRecords by SpendStore.records.collectAsStateWithLifecycle()
    var hideAmounts by rememberSaveable { mutableStateOf(AmountPrivacy.hideAmounts(context)) }

    // What "Clear Old Receipts" must spare: the photo behind the result screen the
    // user is currently looking at, so it can't vanish out from under them, and
    // every photo the pending import batch still needs.
    // The Receipts list opened from Spending carries the month it was reached
    // from; null = all receipts. Both this and the category drill-down hold a
    // non-null value only while their screen is open (remember, not Saveable —
    // transient nav state that needn't survive a process kill).
    var receiptsNav by remember { mutableStateOf<ReceiptsNav?>(null) }
    var categoryNav by remember { mutableStateOf<CategoryNav?>(null) }

    // Only the camera path offers to keep a copy: a photo-library import is
    // already in the library, so saving it back would just duplicate it.
    val startScan = rememberDocumentScanLauncher(onImage = { bytes ->
        if (PhotoSaver.isEnabled(context)) PhotoSaver.save(context, bytes)
        pipeline.scan(bytes)
    })

    // Sub-screens as boolean-gated early returns (a small nav "stack"): GitHub and
    // Debug sit above Settings, so backing out of them returns to Settings.
    if (showGitHubSettings) {
        GitHubSettingsScreen(vm = githubVm, onBack = { showGitHubSettings = false })
        return
    }
    if (showItemRules) {
        ItemRulesScreen(onBack = { showItemRules = false })
        return
    }
    if (showSpending) {
        SpendingScreen(
            onScan = {
                showSpending = false
                startScan()
            },
            onOpenReceipts = { month -> receiptsNav = ReceiptsNav(month) },
            onOpenCategory = { category, title, monthID ->
                categoryNav = CategoryNav(category, title, monthID)
            },
            onBack = { showSpending = false },
        )
        return
    }
    receiptsNav?.let { nav ->
        ReceiptsScreen(
            monthFilter = nav.monthFilter,
            githubConfigured = ghConfigured,
            exportRunning = exportRunning,
            exportMessage = exportMessage,
            onExportEntries = { entries -> githubVm.export(entries) },
            onConfigureExport = { showGitHubSettings = true },
            onBack = { receiptsNav = null },
        )
        return
    }
    categoryNav?.let { nav ->
        CategoryItemsScreen(
            category = nav.category,
            title = nav.title,
            monthID = nav.monthID,
            onBack = { categoryNav = null },
        )
        return
    }
    if (showDebug) {
        DebugInfoScreen(onBack = { showDebug = false })
        return
    }
    if (showDataDump) {
        DataDumpScreen(onBack = { showDataDump = false })
        return
    }
    if (showPrivacy) {
        PrivacyPolicyScreen(onBack = { showPrivacy = false })
        return
    }
    if (showAcknowledgements) {
        AcknowledgementsScreen(onBack = { showAcknowledgements = false })
        return
    }
    if (showOriginalReceipt && image != null) {
        OriginReceiptScreen(imageData = image, onBack = { showOriginalReceipt = false })
        return
    }
    (status as? ScanStatus.Done)?.let { done ->
        if (showJsonPreview) {
            ReceiptJsonScreen(
                result = done.result,
                wallMs = done.wallMs,
                onBack = { showJsonPreview = false },
            )
            return
        }
    }
    if (showBatch) {
        BatchImportScreen(
            batch = batch,
            exportRunning = exportRunning,
            exportMessage = exportMessage,
            githubConfigured = ghConfigured,
            onExport = { entries ->
                githubVm.export(entries) { success -> if (success) batch.removeParsed() }
            },
            onConfigureGitHub = { showGitHubSettings = true },
            onBack = { showBatch = false },
        )
        return
    }
    if (showSettings) {
        SettingsScreen(
            skipOrientation = skipOrientation,
            onSkipOrientationChange = { pipeline.setSkipOrientation(it) },
            onRunSample = {
                showSettings = false
                pipeline.scanBundledSample()
            },
            githubConnected = ghConnected,
            githubAccount = ghAccount,
            onOpenGitHub = { showGitHubSettings = true },
            onOpenItemRules = { showItemRules = true },
            onOpenDebug = { showDebug = true },
            onOpenDataDump = { showDataDump = true },
            onOpenPrivacy = { showPrivacy = true },
            onOpenAcknowledgements = { showAcknowledgements = true },
            onBack = { showSettings = false },
        )
        return
    }

    val isDone = status is ScanStatus.Done

    Scaffold(
        containerColor = groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(if (isDone) "" else "BeanBeaver") },
                navigationIcon = {
                    if (isDone || status is ScanStatus.Failed) {
                        IconButton(onClick = { pipeline.reset() }) {
                            Icon(Icons.Default.Home, contentDescription = "Home")
                        }
                    }
                },
                actions = {
                    val done = status as? ScanStatus.Done
                    if (done != null) {
                        ResultOverflowMenu(
                            hasImage = capturedImage != null,
                            isPremium = Entitlements.isPremium(context),
                            onShowOriginal = { showOriginalReceipt = true },
                            onViewJson = { showJsonPreview = true },
                            onExportMoneyManager = {
                                shareMoneyManager(context, listOf(done.result))
                            },
                        )
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val s = status) {
                is ScanStatus.Idle -> HomePane(
                    onScan = startScan,
                    onImportPhotos = { showBatch = true },
                    spendMonth = if (spendRecords.isEmpty()) null
                    else SpendSummary.month(
                        SpendSummary.defaultMonthId(from = spendRecords),
                        from = spendRecords,
                    ),
                    hideAmounts = hideAmounts,
                    onToggleAmounts = {
                        hideAmounts = !hideAmounts
                        AmountPrivacy.setHideAmounts(context, hideAmounts)
                    },
                    onOpenSpending = { showSpending = true },
                    onOpenReceipts = { receiptsNav = ReceiptsNav(null) },
                    exportReady = ghConfigured,
                    onExport = { showGitHubSettings = true },
                    onSettings = { showSettings = true },
                )
                is ScanStatus.Scanning -> ScanningPane(
                    progress = progress.toFloat(),
                    stepLabel = stepLabel,
                )
                is ScanStatus.Failed -> FailedPane(
                    message = s.message,
                    onRetry = { pipeline.reset() },
                )
                is ScanStatus.Done -> ResultPane(
                    result = s.result,
                    wallMs = s.wallMs,
                    exportRunning = exportRunning,
                    exportMessage = exportMessage,
                    onExport = {
                        if (ghConfigured) {
                            githubVm.export(
                                LedgerEntry.make(context, s.result, capturedImage, s.wallMs))
                        } else {
                            showGitHubSettings = true
                        }
                    },
                    onScanAnother = { pipeline.reset() },
                )
            }
        }
    }

    // Export outcome (PR opened / failed), surfaced over whatever's on screen.
    exportResult?.let { res ->
        AlertDialog(
            onDismissRequest = { githubVm.clearExportResult() },
            title = { Text(res.title) },
            text = { Text(res.message) },
            confirmButton = {
                if (res.url != null) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.url))) }
                        githubVm.clearExportResult()
                    }) { Text("Open") }
                } else {
                    TextButton(onClick = { githubVm.clearExportResult() }) { Text("OK") }
                }
            },
            dismissButton = if (res.url != null) {
                { TextButton(onClick = { githubVm.clearExportResult() }) { Text("OK") } }
            } else null,
        )
    }
}

/**
 * The result screen's overflow menu — the original photo, the raw parse, and the
 * downstream exports that aren't the primary "Export to GitHub" button. Android
 * twin of the iOS result toolbar's `ellipsis.circle` menu.
 */
@Composable
private fun ResultOverflowMenu(
    hasImage: Boolean,
    isPremium: Boolean,
    onShowOriginal: () -> Unit,
    onViewJson: () -> Unit,
    onExportMoneyManager: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Show Original Receipt") },
            enabled = hasImage,
            leadingIcon = { Icon(Icons.Default.Photo, contentDescription = null) },
            onClick = { expanded = false; onShowOriginal() },
        )
        DropdownMenuItem(
            text = { Text("View Details JSON") },
            leadingIcon = { Icon(Icons.Default.DataObject, contentDescription = null) },
            onClick = { expanded = false; onViewJson() },
        )
        if (isPremium) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Export to Money Manager") },
                leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) },
                onClick = { expanded = false; onExportMoneyManager() },
            )
        }
    }
}

/**
 * Build the Money Manager `.xlsx` and hand it straight to the share sheet. A
 * failure here is reported in place rather than through the GitHub export's
 * result dialog — this path never touches the network.
 */
private fun shareMoneyManager(context: Context, results: List<ReceiptResult>) {
    runCatching { MoneyManagerExport.makeFile(context, results) }
        .onSuccess { file ->
            ShareFile.share(
                context, file, ShareFile.XLSX_MIME, "Export to Money Manager")
            // Marked at presentation, not confirmed delivery — the share sheet may
            // be cancelled — which is why the Receipts row says "Shared".
            SpendStore.markShared(context, results)
        }
        .onFailure {
            DebugInfoStore.recordExportFailure(
                context, "export to Money Manager", it.message ?: it.toString())
            Toast.makeText(
                context, "Couldn't build the spreadsheet.", Toast.LENGTH_LONG).show()
        }
}

// MARK: - Home

@Composable
private fun HomePane(
    onScan: () -> Unit,
    onImportPhotos: () -> Unit,
    spendMonth: SpendSummary.Month?,
    hideAmounts: Boolean,
    onToggleAmounts: () -> Unit,
    onOpenSpending: () -> Unit,
    onOpenReceipts: () -> Unit,
    exportReady: Boolean,
    onExport: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(
            "What happens in your wallet, stays in your wallet.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        // The month's tracked spend, right on the home screen — the one number
        // the app exists to produce. Hidden until something has been scanned: a
        // card reading $0.00 is worse than no card. Both the card and the
        // Spending screen open on the newest month with receipts, so they can't
        // advertise one month and hand you another.
        spendMonth?.let { month ->
            SpendCard(
                month = month,
                hideAmounts = hideAmounts,
                onToggleAmounts = onToggleAmounts,
                onOpenSpending = onOpenSpending,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan a Receipt", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onImportPhotos,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import from Photos", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onOpenReceipts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Photo, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Receipts", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (exportReady) "Export: GitHub" else "Export: set up GitHub",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            BbQuietButton(text = "Settings", onClick = onSettings)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Receipts are scanned and parsed on your device. Nothing leaves it unless you " +
                    "export — and then only to your own GitHub ledger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The current month's tracked spend, live on the home screen — the way through
 * to `SpendingView`. Two sibling tap targets, not one nested in the other: an
 * eye laid over the card's own Button would lose the hit test to it, so tapping
 * the eye pushed Spending instead of unmasking.
 */
@Composable
private fun SpendCard(
    month: SpendSummary.Month,
    hideAmounts: Boolean,
    onToggleAmounts: () -> Unit,
    onOpenSpending: () -> Unit,
) {
    BbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenSpending),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    month.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (hideAmounts) AmountPrivacy.PLACEHOLDER else priceCurrency(month.tracked),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "tracked spend · ${month.receiptCount} receipt${if (month.receiptCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleAmounts) {
                Icon(
                    if (hideAmounts) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (hideAmounts) "Show amounts" else "Hide amounts",
                )
            }
        }
    }
}

// MARK: - Scanning

@Composable
private fun ScanningPane(progress: Float, stepLabel: String) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.9f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.9f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clip(RoundedCornerShape(percent = 50))
                    .background(BbAccentSoft),
            )
            Icon(
                Icons.Default.DocumentScanner,
                contentDescription = null,
                tint = BbAccent,
                modifier = Modifier.size(34.dp),
            )
        }
        Text("Reading your receipt…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.width(220.dp),
        )
        Text(
            stepLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Failed

@Composable
private fun FailedPane(message: String, onRetry: () -> Unit) {
    BbCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(BbAccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = BbAccent,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text("Couldn't read that receipt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Try Again", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// MARK: - Result

@Composable
private fun ResultPane(
    result: ReceiptResult,
    wallMs: Double,
    exportRunning: Boolean,
    exportMessage: String?,
    onExport: () -> Unit,
    onScanAnother: () -> Unit,
) {
    ReceiptCard(result = result, wallMs = wallMs)

    Button(
        onClick = onExport,
        enabled = !exportRunning,
        modifier = Modifier.fillMaxWidth(),
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
            Text("Export to GitHub", fontWeight = FontWeight.SemiBold)
        }
    }

    OutlinedButton(onClick = onScanAnother, modifier = Modifier.fillMaxWidth()) {
        Text("Scan another", fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The parsed receipt — merchant, totals, items, warnings, and (collapsed) the
 * generated beancount + per-phase timings. The Kotlin twin of iOS `ReceiptCard`.
 */
@Composable
internal fun ReceiptCard(result: ReceiptResult, wallMs: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BbCard {
            ReceiptHeader(result)
            if (result.items.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    result.items.forEach { ItemRow(it) }
                }
            }
        }

        if (result.warnings.isNotEmpty()) {
            WarningsBanner(result.warnings)
        }

        AccountingDetails(result, wallMs)
    }
}

@Composable
private fun ReceiptHeader(result: ReceiptResult) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                titleCase(result.merchant),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            // A `Suggested` match isn't trusted enough to replace the OCR'd name —
            // offer the canonical guess quietly rather than rewriting it.
            val match = result.merchantMatch
            if (match.status == MerchantMatchStatus.SUGGESTED && match.canonical != null) {
                Text(
                    "Did you mean ${titleCase(match.canonical!!)}?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val date = friendlyDate(result.date)
            if (date != null) {
                Text(
                    buildString {
                        append(date)
                        if (result.dateIsPlaceholder) append(" (estimated)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (result.subtotal != null || result.tax != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                result.subtotal?.let { SubtotalRow("Subtotal", it) }
                result.tax?.let { SubtotalRow("Tax", it) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Total",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatPrice(result.total).text,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = BbAccent,
            )
        }
    }
}

@Composable
private fun SubtotalRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatPrice(value).text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ItemRow(item: ReceiptItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                titleCase(item.description),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            TagRow(item)
        }
        if (item.quantity > 1) {
            Text(
                "×${item.quantity}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        val price = formatPrice(item.price)
        Text(
            price.text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (price.isNegative) BbAccent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The item's classification from its tags: most-specific tag as an accent chip,
 * broader tags as quiet context. No tags → "Uncategorized". iOS `tagRow`.
 */
@Composable
private fun TagRow(item: ReceiptItem) {
    val display = remember(item) { tagDisplay(item.tags) }
    if (display.primary != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChip(display.primary!!)
            if (display.rest.isNotEmpty()) {
                Text(
                    display.rest.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    } else {
        Text(
            "Uncategorized",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningsBanner(warnings: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BbAccentSoft)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = BbAccent, modifier = Modifier.size(18.dp))
            Text("Heads up", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = BbAccent)
        }
        warnings.forEach { w ->
            Text(w, style = MaterialTheme.typography.labelSmall, color = BbAccent)
        }
    }
}

/**
 * Collapsible "Accounting details" (iOS `DisclosureGroup`): the generated
 * beancount, plus the per-phase scan timings. Collapsed by default so the card
 * leads with the human-readable receipt, not the ledger.
 */
@Composable
private fun AccountingDetails(result: ReceiptResult, wallMs: Double) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BbCard(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Accounting details",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    result.beancount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            TimingBreakdown(result.timings, wallMs)
        }
    }
}

/**
 * Per-phase scan timing. Each core-reported stage gets a bar proportional to the
 * slowest phase, so the bottleneck is obvious. `Rust total` is what the core
 * measured; `Wall` is what the app timed around the whole FFI call; the gap is
 * `Overhead` — JNI marshalling plus the one-off ONNX model load on the first scan.
 */
@Composable
private fun TimingBreakdown(timings: ScanTimings, wallMs: Double) {
    val phases = remember(timings) { timings.spans.map { it.phase.label() to it.ms } }
    val maxMs = remember(phases) { (phases.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1.0) }
    val overheadMs = (wallMs - timings.totalMs).coerceAtLeast(0.0)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Timing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        phases.forEach { (label, ms) ->
            TimingRow(label, ms, (ms / maxMs).toFloat())
        }
        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        TimingTotalRow("Rust total", timings.totalMs)
        TimingTotalRow("Overhead (JNI · 1st-scan model load)", overheadMs)
        TimingTotalRow("Wall (scan)", wallMs, emphasize = true)
    }
}

@Composable
private fun TimingRow(label: String, ms: Double, fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                "${"%.0f".format(ms)} ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BbAccent),
                )
            }
        }
    }
}

@Composable
private fun TimingTotalRow(label: String, ms: Double, emphasize: Boolean = false) {
    val style = if (emphasize) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = style)
        Spacer(Modifier.weight(1f))
        Text("${"%.0f".format(ms)} ms", style = style, fontFamily = FontFamily.Monospace)
    }
}
