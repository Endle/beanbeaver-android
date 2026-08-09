package com.zhenbo.beanbeaver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The `foss` twin of the `full` flavour's Play-services document scanner —
 * deliberately the same signature, so [BeanBeaverApp] and the Spending screen's
 * empty state call it without knowing which flavour they are in.
 *
 * F-Droid does not accept Google Play services, and the ML Kit document scanner
 * is the app's only user of it. What that costs is the *guided capture* UI:
 * edge detection, perspective correction and the retake loop all live inside the
 * Play-services activity and have no FOSS equivalent here. What it does not cost
 * is scanning — this opens the system photo picker, so the user takes the photo
 * with their own camera app and picks it, and the bytes reach
 * `ReceiptPipeline.scan` by exactly the same path.
 *
 * Deskew is therefore doing more work in this flavour than in `full`: the core's
 * own deskew (receipt-core, shipped since v0.7.2) is the only thing correcting a
 * skewed photo. That is the same situation as picking an existing photo in the
 * `full` flavour, which is already the common case, so this is a degradation of
 * capture ergonomics rather than of the parse.
 *
 * Uses PickVisualMedia (single) rather than PickMultipleVisualMedia: this entry
 * point scans one receipt into the detail view. Batch import has its own
 * multi-select picker in [BatchImportScreen], and it is GMS-free in both flavours.
 */
@Composable
fun rememberDocumentScanLauncher(onImage: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Read off the main thread — a receipt photo is a few MB, and this
            // mirrors what the full flavour does with the scanner's output.
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) onImage(bytes)
        }
    }

    return {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
