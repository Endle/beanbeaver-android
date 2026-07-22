package com.beanbeaver.app.receipt

import android.content.Context
import com.beanbeaver.bbreceiptkit.ReceiptScanner
import uniffi.bb_receipt_ffi.OcrSession

/**
 * Process-wide OCR session, shared by everything that scans. The models are large
 * (load once), and one [OcrSession] can't be driven by two scans at the same time,
 * so a single instance is handed to both [ReceiptPipeline] (the single camera /
 * photo scan) and [ReceiptBatch] (the photo-library backlog). Kotlin twin of iOS
 * `OcrSessionProvider`.
 *
 * Reloads only when the orientation-classifier preference flips, since that
 * changes which of the three models the session opens.
 */
object OcrSessionProvider {
    private const val PREFS_NAME = "beanbeaver"
    private const val KEY_SKIP_ORIENTATION = "skipOrientationCheck"

    @Volatile
    private var cached: OcrSession? = null

    @Volatile
    private var loadedWithOrientationCls: Boolean? = null

    /** The shared session, loading (or reloading) it if the cls preference changed. */
    fun loaded(context: Context): OcrSession {
        val useCls = !skipOrientation(context)
        cached?.let { if (loadedWithOrientationCls == useCls) return it }
        synchronized(this) {
            cached?.let { if (loadedWithOrientationCls == useCls) return it }
            val dir = ModelStore.ensureModels(context)
            val s = ReceiptScanner.load(dir, useOrientationCls = useCls)
            cached = s
            loadedWithOrientationCls = useCls
            return s
        }
    }

    /** Whether the textline-orientation classifier is skipped (user setting). */
    fun skipOrientation(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SKIP_ORIENTATION, false)
}
