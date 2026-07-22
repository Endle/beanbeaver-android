package com.beanbeaver.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.beanbeaver.app.receipt.ReceiptPipeline
import com.beanbeaver.app.receipt.ScanStatus
import com.beanbeaver.app.receipt.label
import com.beanbeaver.app.receipt.totalMs
import com.beanbeaver.app.ui.theme.BbAccent
import com.beanbeaver.app.ui.theme.BbAccentSoft
import com.beanbeaver.app.ui.theme.groupedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bb_receipt_ffi.MerchantMatchStatus
import uniffi.bb_receipt_ffi.ReceiptItem
import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.ScanTimings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeanBeaverApp(
    pipeline: ReceiptPipeline = viewModel(),
) {
    val status by pipeline.status.collectAsStateWithLifecycle()
    val progress by pipeline.scanProgress.collectAsStateWithLifecycle()
    val stepLabel by pipeline.scanStepLabel.collectAsStateWithLifecycle()
    val capturedImage by pipeline.capturedImage.collectAsStateWithLifecycle()
    val skipOrientation by pipeline.skipOrientation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Full-screen review of the original photo, opened from the result screen.
    var showOriginalReceipt by rememberSaveable { mutableStateOf(false) }
    // The Android twin of iOS Settings (sample scan, skip-orientation, versions).
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val image = capturedImage
    if (showOriginalReceipt && image != null) {
        OriginReceiptScreen(imageData = image, onBack = { showOriginalReceipt = false })
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
            onBack = { showSettings = false },
        )
        return
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) pipeline.scan(bytes)
        }
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
                    if (isDone && capturedImage != null) {
                        IconButton(onClick = { showOriginalReceipt = true }) {
                            Icon(Icons.Default.Photo, contentDescription = "Show original receipt")
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val s = status) {
                is ScanStatus.Idle -> HomePane(
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
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
                    onScanAnother = { pipeline.reset() },
                )
            }
        }
    }
}

// MARK: - Home

@Composable
private fun HomePane(
    onPickPhoto: () -> Unit,
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

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPickPhoto,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import from Photos", fontWeight = FontWeight.SemiBold)
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
                "Receipts are scanned and parsed on your device. Nothing leaves it — " +
                    "sync isn't in this Android preview yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
    onScanAnother: () -> Unit,
) {
    ReceiptCard(result = result, wallMs = wallMs)

    Button(onClick = onScanAnother, modifier = Modifier.fillMaxWidth()) {
        Text("Scan another", fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The parsed receipt — merchant, totals, items, warnings, and (collapsed) the
 * generated beancount + per-phase timings. The Kotlin twin of iOS `ReceiptCard`.
 */
@Composable
private fun ReceiptCard(result: ReceiptResult, wallMs: Double) {
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
