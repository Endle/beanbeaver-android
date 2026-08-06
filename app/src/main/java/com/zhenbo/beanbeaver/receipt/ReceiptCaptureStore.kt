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
 * (The single-scan [ReceiptPipeline] also writes its one image here, mirroring
 * iOS — it's the durable thing a parse can always be re-derived from.)
 *
 * A capture is no longer expired by any heuristic here — once a scan succeeds,
 * its photo is owned by that receipt's `SpendRecord` ([SpendStore]) and lives
 * until the user deletes the receipt or clears its photo. This type only knows
 * how to name, total, and delete captures; it has no opinion about which ones a
 * caller should keep.
 */
object ReceiptCaptureStore {
    private const val PREFIX = "receipt_capture_"

    fun directory(context: Context): File =
        File(context.filesDir, "captures").apply { mkdirs() }

    fun file(context: Context, filename: String): File =
        File(directory(context), filename)

    /** A fresh, collision-free capture file (its bytes not yet written). */
    fun newCaptureFile(context: Context): File =
        File(directory(context), "${PREFIX}${UUID.randomUUID()}.jpg")

    /** Delete one capture by name. A no-op if it's already gone. The caller —
     *  `SpendStore`, today — is the one that knows whether anything still needs
     *  this photo. */
    fun delete(context: Context, filename: String) {
        runCatching { file(context, filename).delete() }
    }

    /** How much disk every kept receipt photo is using, for the Settings row. */
    fun totalBytes(context: Context): Long =
        directory(context).listFiles()?.sumOf { it.length() } ?: 0L
}
