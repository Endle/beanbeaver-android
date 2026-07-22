package com.beanbeaver.app.receipt

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
}
