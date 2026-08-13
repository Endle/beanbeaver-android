package com.zhenbo.beanbeaver.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The `foss` twin of the `full` flavour's Play-services document scanner —
 * deliberately the same signature, so [BeanBeaverApp] and the Spending screen's
 * empty state call it without knowing which flavour they are in.
 *
 * F-Droid does not accept Google Play services, and the ML Kit document scanner
 * is the app's only user of it. This flavour therefore captures in-process, with
 * [CameraCaptureActivity] (CameraX): preview, shutter, review, retake.
 *
 * What it still does not do is *guided* capture — live document edge detection
 * and perspective correction, which live inside the Play-services activity in
 * `full`. Note that nothing else in the stack compensates for that: receipt-core
 * excludes image deskew by design (`receipt-image/src/lib.rs`), and the deskew
 * that does ship is detection-level, shearing OCR boxes to straighten sloped
 * text rows — not a perspective correction on pixels. So a keystoned photo stays
 * keystoned. Closing that gap is a separate change.
 *
 * Batch import keeps its own multi-select photo picker in [BatchImportScreen],
 * which is GMS-free in both flavours; this entry point is the one-receipt path.
 */
@Composable
fun rememberDocumentScanLauncher(onImage: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val capture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val path = result.data?.getStringExtra(CameraCaptureActivity.EXTRA_IMAGE_PATH)
            ?: return@rememberLauncherForActivityResult
        scope.launch {
            // Read and clean up off the main thread — a full-quality receipt shot
            // is several MB, and the capture file is ours to delete once the bytes
            // are in hand.
            val bytes = withContext(Dispatchers.IO) {
                val file = File(path)
                val data = runCatching { file.readBytes() }.getOrNull()
                file.delete()
                data
            }
            if (bytes != null) onImage(bytes)
        }
    }

    return { capture.launch(Intent(context, CameraCaptureActivity::class.java)) }
}
