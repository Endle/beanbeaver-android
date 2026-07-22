package com.beanbeaver.app.ui

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remembers a launcher for the Play-services document scanner — guided capture
 * with automatic edge-detection and deskew, the Android analog of iOS VisionKit.
 * Returns a `start()` to wire to a button; the cropped JPEG's bytes come back
 * through [onImage] (read off the main thread), ready for `ReceiptPipeline.scan`.
 *
 * The scanner UI and its model download are handled by Google Play services, so
 * this needs no CAMERA permission of our own. `getStartScanIntent` fails when the
 * module can't be fetched (no Play services / offline first run) — we log it and
 * surface a toast rather than crash, mirroring iOS gating the button off when the
 * document camera isn't supported.
 */
@Composable
fun rememberDocumentScanLauncher(onImage: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val uri = scan?.pages?.firstOrNull()?.imageUri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) onImage(bytes)
        }
    }

    return start@{
        val activity = context as? Activity ?: return@start
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Log.e("DocumentScan", "failed to start document scanner", e)
                Toast.makeText(
                    context,
                    "Scanner unavailable — you can still Import from Photos.",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }
}
