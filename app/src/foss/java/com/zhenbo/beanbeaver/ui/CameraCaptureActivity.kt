package com.zhenbo.beanbeaver.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zhenbo.beanbeaver.ui.theme.BeanBeaverTheme
import de.schliweb.makeacopy.ml.corners.BbDocQuad
import de.schliweb.makeacopy.ml.corners.CornerDetector
import de.schliweb.makeacopy.ml.corners.DetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * In-app receipt capture for the `foss` flavour — the FOSS answer to the `full`
 * flavour's ML Kit document scanner, which cannot ship to F-Droid.
 *
 * An Activity rather than a screen inside `MainActivity` on purpose: it keeps
 * `rememberDocumentScanLauncher`'s signature a plain `() -> Unit` returning bytes
 * through an ActivityResult, exactly as the ML Kit version does, so
 * `BeanBeaverApp.kt` still cannot tell which flavour it is in.
 *
 * Document detection is DocQuadNet, vendored from MakeACopy (see
 * `de.schliweb.makeacopy.ml`). It runs twice, for two different jobs:
 *
 *  - on the **preview**, rate-limited and smoothed, purely to draw the quad so
 *    the user can see what will be kept before pressing the shutter;
 *  - on the **captured still**, once, at full resolution — those are the corners
 *    that actually crop, because a downscaled preview frame would place them
 *    less precisely than the image being cropped deserves.
 */
class CameraCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeanBeaverTheme {
                CaptureFlow(
                    onConfirm = { file ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_IMAGE_PATH, file.absolutePath))
                        finish()
                    },
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The ONNX session holds the 13 MB model; this screen is its only user.
        BbDocQuad.release()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "com.zhenbo.beanbeaver.EXTRA_IMAGE_PATH"
        internal const val TAG = "CameraCapture"
    }
}

/** A detected quad plus the frame it was measured in, so the overlay can scale it. */
private data class LiveQuad(val corners: Array<DoubleArray>, val frameW: Int, val frameH: Int)

/** The shot under review: what the parser will get, and whether it was cropped. */
private data class Shot(val file: File, val bitmap: Bitmap?, val cropped: Boolean)

@Composable
private fun CaptureFlow(onConfirm: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Shot?>(null) }
    var working by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denied = !ok
    }

    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    when {
        working -> Busy()

        shot != null -> ReviewShot(
            shot = shot!!,
            onRetake = { shot!!.file.delete(); shot = null },
            onUse = { onConfirm(shot!!.file) },
        )

        granted -> CameraPreview(
            onCaptured = { file, scope ->
                working = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { cropCaptured(context, file) }
                    shot = result
                    working = false
                }
            },
            onCancel = onCancel,
        )

        denied -> PermissionRefused(onCancel = onCancel, onRetry = {
            denied = false
            askPermission.launch(Manifest.permission.CAMERA)
        })

        else -> Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
private fun Busy() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.size(16.dp))
            Text("Finding the receipt…", color = Color.White)
        }
    }
}

@Composable
private fun CameraPreview(
    onCaptured: (File, kotlinx.coroutines.CoroutineScope) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    // One background thread for inference: STRATEGY_KEEP_ONLY_LATEST means a slow
    // frame is dropped rather than queued, so a single worker cannot fall behind.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val imageCapture = remember {
        ImageCapture.Builder()
            // A receipt is small text on thermal paper; the parse is only as good
            // as the pixels, so trade shutter latency for quality.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val previewView = remember {
        // FIT_CENTER, not the default FILL_CENTER: the overlay maps detector
        // coordinates onto the preview with one uniform scale plus a letterbox
        // offset, which is only true when nothing is cropped off the edges.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    var liveQuad by remember { mutableStateOf<LiveQuad?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val detector: CornerDetector? = runCatching { BbDocQuad.forPreview(context) }
            .onFailure { Log.e(CameraCaptureActivity.TAG, "no preview detector", it) }
            .getOrNull()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { ia ->
                ia.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        if (detector == null) return@setAnalyzer
                        val upright = proxy.toBitmap().rotated(proxy.imageInfo.rotationDegrees)
                        val result = detector.detect(upright, context)
                        liveQuad = result.takeIf { it.success }
                            ?.cornersOriginalTLTRBRBL
                            ?.let { LiveQuad(it, upright.width, upright.height) }
                    } catch (e: Throwable) {
                        Log.w(CameraCaptureActivity.TAG, "preview detect failed", e)
                    } finally {
                        proxy.close()
                    }
                }
            }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    analysis,
                )
            } catch (e: Exception) {
                Log.e(CameraCaptureActivity.TAG, "failed to bind camera use cases", e)
            }
        }, mainExecutor)

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // The quad, drawn over the preview. Purely advisory — the crop is decided
        // again on the still, so what is drawn here is an indication of framing,
        // not a promise about the exact edges.
        liveQuad?.let { q ->
            Canvas(Modifier.fillMaxSize()) {
                val scale = minOf(size.width / q.frameW, size.height / q.frameH)
                val dx = (size.width - q.frameW * scale) / 2f
                val dy = (size.height - q.frameH * scale) / 2f
                val pts = q.corners.map {
                    Offset(dx + (it[0] * scale).toFloat(), dy + (it[1] * scale).toFloat())
                }
                for (i in pts.indices) {
                    drawLine(
                        color = Color(0xFF4CAF50),
                        start = pts[i],
                        end = pts[(i + 1) % pts.size],
                        strokeWidth = 6f,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (liveQuad != null) "Receipt found — hold steady."
                else "Fill the frame with the receipt, straight on.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            // Three equal slots so the shutter is centred on the screen rather
            // than centred on whatever is left over beside the Cancel button.
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            takePicture(context, imageCapture, mainExecutor) { file ->
                                busy = false
                                if (file != null) onCaptured(file, scope)
                            }
                        },
                        enabled = !busy,
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Take photo",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Rotate an analysis frame upright, so detector coordinates match what is on screen. */
private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}

/**
 * Detect on the full-resolution still and crop to the receipt.
 *
 * Falls back to the untouched capture whenever detection fails or the quad is
 * effectively the whole frame — in that case the original JPEG is handed on with
 * its EXIF intact, exactly as before this feature existed.
 */
private fun cropCaptured(context: Context, file: File): Shot {
    val source = runCatching {
        // ImageDecoder honours EXIF orientation; BitmapFactory does not, and a
        // sideways receipt would be detected sideways.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }.getOrElse {
        Log.w(CameraCaptureActivity.TAG, "decode failed; using capture as-is", it)
        return Shot(file, null, cropped = false)
    }

    val detection: DetectionResult? = runCatching {
        BbDocQuad.forStill(context).detect(source, context)
    }.onFailure { Log.w(CameraCaptureActivity.TAG, "still detect failed", it) }.getOrNull()

    val cropped = cropToQuad(source, detection)
        ?: return Shot(file, source, cropped = false)

    val out = File.createTempFile("crop", ".jpg", context.cacheDir)
    val ok = runCatching {
        FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }.isSuccess
    if (!ok) {
        out.delete()
        return Shot(file, source, cropped = false)
    }
    file.delete()
    return Shot(out, cropped, cropped = true)
}

private fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onResult: (File?) -> Unit,
) {
    // cacheDir, not filesDir: this file only has to survive the trip back to
    // rememberDocumentScanLauncher, which reads it and deletes it.
    val file = File.createTempFile("capture", ".jpg", context.cacheDir)
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onResult(file)

            override fun onError(exception: ImageCaptureException) {
                Log.e(CameraCaptureActivity.TAG, "capture failed", exception)
                file.delete()
                onResult(null)
            }
        },
    )
}

@Composable
private fun ReviewShot(shot: Shot, onRetake: () -> Unit, onUse: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        shot.bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Captured receipt",
                modifier = Modifier.fillMaxSize().padding(bottom = 112.dp),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (shot.cropped) "Cropped to the receipt."
                else "Kept the whole frame — no receipt edges found.",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = onRetake) { Text("Retake") }
                Button(onClick = onUse) { Text("Use Photo") }
            }
        }
    }
}

@Composable
private fun PermissionRefused(onCancel: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "BeanBeaver needs the camera to photograph a receipt. Nothing leaves " +
                "your device — the scan runs here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Not Now") }
            Button(onClick = onRetry) { Text("Allow Camera") }
        }
    }
}
