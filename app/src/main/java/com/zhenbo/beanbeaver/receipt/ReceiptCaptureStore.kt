package com.zhenbo.beanbeaver.receipt

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * On-disk home for imported receipt JPEGs, under app-private storage. The batch
 * keeps its photos here rather than in memory: a backlog of twenty full-resolution
 * images is a lot to hold at once, and the photo is the durable thing a parse is
 * always re-derivable from. Kotlin twin of iOS `ReceiptCaptureStore`.
 *
 * (The single-scan [ReceiptPipeline] still holds its one image in memory — it
 * scans one receipt at a time, so there's nothing to spill to disk.)
 */
object ReceiptCaptureStore {
    fun directory(context: Context): File =
        File(context.filesDir, "captures").apply { mkdirs() }

    fun file(context: Context, filename: String): File =
        File(directory(context), filename)

    /** A fresh, collision-free capture file (its bytes not yet written). */
    fun newCaptureFile(context: Context): File =
        File(directory(context), "receipt_capture_${UUID.randomUUID()}.jpg")

    /** How much disk every kept receipt photo is using, for the Settings row. */
    fun totalBytes(context: Context): Long =
        directory(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** What a purge removed, so the caller can say so concretely. */
    data class ClearResult(val count: Int, val bytes: Long)

    /**
     * Delete every capture except the ones named in [keeping]. Android twin of
     * iOS `ReceiptCaptureStore.clearOld(keeping:)`.
     *
     * The exemptions matter: the photo behind a result screen the user is still
     * looking at must not vanish from under them, and neither must one the
     * pending import batch still needs to parse or export.
     */
    fun clearOld(context: Context, keeping: Set<String>): ClearResult {
        var count = 0
        var bytes = 0L
        directory(context).listFiles()?.forEach { file ->
            if (file.name in keeping) return@forEach
            val size = file.length()
            if (file.delete()) {
                count++
                bytes += size
            }
        }
        return ClearResult(count, bytes)
    }
}
