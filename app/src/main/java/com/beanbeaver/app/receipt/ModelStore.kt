package com.beanbeaver.app.receipt

import android.content.Context
import java.io.File

/**
 * Ships the three PP-OCRv5 ONNX models as APK assets and copies them to
 * app-private storage so Rust/`ort` can open real paths (same filenames the
 * iOS bundle uses).
 */
object ModelStore {
    val modelFiles = listOf(
        "PP-OCRv5_mobile_det.onnx",
        "PP-OCRv5_mobile_rec.onnx",
        "PP-LCNet_x1_0_textline_ori.onnx",
    )

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    /**
     * Ensure models are present under [modelsDir]. Idempotent: skips a file when
     * size already matches the asset (cheap staleness check).
     */
    fun ensureModels(context: Context): File {
        val dir = modelsDir(context)
        val am = context.assets
        for (name in modelFiles) {
            val out = File(dir, name)
            val assetPath = "models/$name"
            // openFd only works when the asset is stored uncompressed; fall back
            // to "missing or empty" if the FD path is unavailable.
            val needCopy = try {
                val assetLen = am.openFd(assetPath).use { it.length }
                !out.isFile || out.length() != assetLen
            } catch (_: Exception) {
                !out.isFile || out.length() == 0L
            }
            if (!needCopy) continue
            am.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dir
    }
}
