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
    /**
     * What every capture filename starts with. Load-bearing, not cosmetic: this
     * directory is shared with `spend.json`, `batch.json` and `item_rules.json`,
     * so anything totalling or sweeping "the photos" has to filter on it. iOS
     * spells the same constant `filenamePrefix` for the same reason.
     */
    const val FILENAME_PREFIX = "receipt_capture_"

    fun directory(context: Context): File =
        File(context.filesDir, "captures").apply { mkdirs() }

    fun file(context: Context, filename: String): File =
        File(directory(context), filename)

    /** A fresh, collision-free capture file (its bytes not yet written). */
    fun newCaptureFile(context: Context): File =
        File(directory(context), "$FILENAME_PREFIX${UUID.randomUUID()}.jpg")

    /**
     * Bytes used by capture JPEGs on disk, orphans from failed scans included.
     *
     * Not what the Settings row shows — that is [SpendStore.totalPhotoBytes],
     * which counts only photos a kept receipt still points at. The two differ by
     * exactly the orphaned captures, and iOS draws the same distinction.
     */
    fun totalBytes(context: Context): Long =
        directory(context).listFiles()
            ?.filter { it.name.startsWith(FILENAME_PREFIX) }
            ?.sumOf { it.length() } ?: 0L

    // There was a `clearOld(keeping:)` sweep here, deleting every capture except
    // the ones a live screen or a pending batch still needed. It is gone, and the
    // exemption list with it: [SpendStore] now owns photo lifetime, so a photo
    // belongs to a record the user can see and delete explicitly rather than to a
    // directory something has to guess the live members of. iOS dropped its twin
    // in the same change.
}
