package com.beanbeaver.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beanbeaver.app.BuildConfig
import com.beanbeaver.app.receipt.ReceiptPipeline
import com.beanbeaver.app.receipt.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bb_receipt_ffi.ReceiptResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeanBeaverApp(
    pipeline: ReceiptPipeline = viewModel(),
) {
    val status by pipeline.status.collectAsStateWithLifecycle()
    val progress by pipeline.scanProgress.collectAsStateWithLifecycle()
    val stepLabel by pipeline.scanStepLabel.collectAsStateWithLifecycle()
    val capturedImage by pipeline.capturedImage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Full-screen review of the original photo, opened from the result screen.
    var showOriginalReceipt by rememberSaveable { mutableStateOf(false) }
    val image = capturedImage
    if (showOriginalReceipt && image != null) {
        OriginReceiptScreen(imageData = image, onBack = { showOriginalReceipt = false })
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (status is ScanStatus.Done) "" else "BeanBeaver",
                    )
                },
                navigationIcon = {
                    if (status is ScanStatus.Done || status is ScanStatus.Failed) {
                        IconButton(onClick = { pipeline.reset() }) {
                            Icon(Icons.Default.Home, contentDescription = "Home")
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
            when (val s = status) {
                is ScanStatus.Idle -> HomePane(
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onSample = { pipeline.scanBundledSample() },
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
                    onHome = { pipeline.reset() },
                    onShowOriginal = if (capturedImage != null) {
                        { showOriginalReceipt = true }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun HomePane(
    onPickPhoto: () -> Unit,
    onSample: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ReceiptLong,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(56.dp),
        )
        Text(
            "On-device receipt → Beancount",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Photos are scanned and parsed on your phone. Nothing leaves the device " +
                "unless you later set up sync (not in this Android MVP yet).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onPickPhoto,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.padding(6.dp))
            Text("Import from Photos")
        }
        OutlinedButton(
            onClick = onSample,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Try bundled sample receipt")
        }
        Spacer(Modifier.height(8.dp))
        AboutVersions()
    }
}

/**
 * App build + the beanbeaver-core (on-device scan engine) version it was
 * compiled against — the Android twin of iOS Settings › About. The core string
 * is injected from the Cargo.lock pin at build time (see app/build.gradle.kts),
 * so it can't drift from the .so actually linked. Include both when reporting a
 * scan issue.
 */
@Composable
private fun AboutVersions() {
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    Text(
        "BeanBeaver $appVersion  ·  beanbeaver-core ${BuildConfig.CORE_VERSION}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScanningPane(progress: Float, stepLabel: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stepLabel, style = MaterialTheme.typography.bodyLarge)
        Text(
            "OCR runs entirely on-device (CPU).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedPane(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan failed", style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.padding(6.dp))
            Text("Back")
        }
    }
}

@Composable
private fun ResultPane(
    result: ReceiptResult,
    wallMs: Double,
    onHome: () -> Unit,
    onShowOriginal: (() -> Unit)?,
) {
    val itemsSummary = remember(result) {
        result.items.take(12).joinToString("\n") { item ->
            val cat = item.category ?: "—"
            "• ${item.description}  ${item.price}  ($cat)"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(result.merchant, style = MaterialTheme.typography.headlineSmall)
        Text(
            buildString {
                append(result.date ?: "no date")
                if (result.dateIsPlaceholder) append(" (placeholder)")
                append("  ·  total ")
                append(result.total)
                result.tax?.let { append("  ·  tax $it") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Scan ${"%.0f".format(wallMs)} ms  ·  Rust total ${"%.0f".format(result.timings.totalMs)} ms",
            style = MaterialTheme.typography.labelMedium,
        )

        if (result.confidence.needsReview) {
            Text(
                "Needs review — some fields look uncertain.",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (itemsSummary.isNotBlank()) {
            Card(colors = CardDefaults.cardColors()) {
                Text(
                    itemsSummary,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (result.warnings.isNotEmpty()) {
            Text("Warnings", style = MaterialTheme.typography.titleSmall)
            result.warnings.forEach { w ->
                Text("• $w", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Beancount", style = MaterialTheme.typography.titleMedium)
        Card {
            SelectionContainer {
                Text(
                    result.beancount,
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (onShowOriginal != null) {
            OutlinedButton(onClick = onShowOriginal, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Show original receipt")
            }
        }

        Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text("Scan another")
        }
    }
}
