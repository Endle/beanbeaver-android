package com.zhenbo.beanbeaver.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.zhenbo.beanbeaver.ui.theme.BeanBeaverTheme
import java.io.File
import java.util.concurrent.Executor

/**
 * In-app receipt capture for the `foss` flavour — the FOSS answer to the `full`
 * flavour's ML Kit document scanner, which cannot ship to F-Droid.
 *
 * An Activity rather than a screen inside [MainActivity] on purpose: it keeps
 * `rememberDocumentScanLauncher`'s signature a plain `() -> Unit` returning bytes
 * through an ActivityResult, exactly as the ML Kit and photo-picker versions do,
 * so `BeanBeaverApp.kt` still cannot tell which flavour it is in and needs no
 * navigation state for a camera.
 *
 * This is capture only: preview, shutter, review, retake. There is no document
 * edge detection or perspective correction here yet — the user frames the
 * receipt themselves. That is still strictly better than the photo picker it
 * replaces, which made them leave the app, shoot in a camera app, come back and
 * find the file.
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

    companion object {
        const val EXTRA_IMAGE_PATH = "com.zhenbo.beanbeaver.EXTRA_IMAGE_PATH"
        private const val TAG = "CameraCapture"
    }
}

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
    // The shot under review. Null means "still framing"; set means the review
    // step owns the screen and the camera is torn down.
    var pending by remember { mutableStateOf<File?>(null) }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denied = !ok
    }

    LaunchedEffect(Unit) {
        if (!granted) askPermission.launch(Manifest.permission.CAMERA)
    }

    when {
        pending != null -> ReviewShot(
            file = pending!!,
            onRetake = { pending!!.delete(); pending = null },
            onUse = { onConfirm(pending!!) },
        )

        granted -> CameraPreview(onCaptured = { pending = it }, onCancel = onCancel)

        denied -> PermissionRefused(onCancel = onCancel, onRetry = {
            denied = false
            askPermission.launch(Manifest.permission.CAMERA)
        })

        else -> Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
private fun CameraPreview(onCaptured: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }
    // Held across recompositions so the shutter can reach the same use case the
    // preview is bound to.
    val imageCapture = remember {
        ImageCapture.Builder()
            // A receipt is small text on thermal paper; the parse is only as good
            // as the pixels, so trade shutter latency for quality.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val previewView = remember { PreviewView(context) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
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
                )
            } catch (e: Exception) {
                Log.e("CameraCapture", "failed to bind camera use cases", e)
            }
        }, executor)

        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Fill the frame with the receipt, straight on.",
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
                            takePicture(context, imageCapture, executor) { file ->
                                busy = false
                                if (file != null) onCaptured(file)
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

private fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onResult: (File?) -> Unit,
) {
    // cacheDir, not filesDir: this file only has to survive the trip back to
    // rememberDocumentScanLauncher, which reads it and deletes it. Anything the
    // user keeps is written by the receipt store from the decoded bytes.
    val file = File.createTempFile("capture", ".jpg", context.cacheDir)
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onResult(file)

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraCapture", "capture failed", exception)
                file.delete()
                onResult(null)
            }
        },
    )
}

@Composable
private fun ReviewShot(file: File, onRetake: () -> Unit, onUse: () -> Unit) {
    val context = LocalContext.current
    // Decode downscaled purely for the preview — the full-resolution file is what
    // gets handed to the parser, untouched.
    val bitmap = remember(file.path) { decodeForPreview(file, context) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Captured receipt",
                modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onRetake) { Text("Retake") }
            Button(onClick = onUse) { Text("Use Photo") }
        }
    }
}

private fun decodeForPreview(file: File, context: Context): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    val target = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
    var sample = 1
    while (bounds.outHeight / sample > target * 2) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
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
