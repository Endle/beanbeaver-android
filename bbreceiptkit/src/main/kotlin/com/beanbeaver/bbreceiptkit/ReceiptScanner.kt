package com.beanbeaver.bbreceiptkit

import uniffi.bb_receipt_ffi.DateYmd
import uniffi.bb_receipt_ffi.OcrSession
import uniffi.bb_receipt_ffi.ReceiptResult
import java.io.File
import java.time.LocalDate

/**
 * Hand-written conveniences over the generated UniFFI glue — Kotlin twin of
 * iOS `BBReceiptKit/Sources/BBReceiptKit/ReceiptScanner.swift`.
 *
 * Generated types live in package `uniffi.bb_receipt_ffi` after
 * `./build-android.sh` runs. Until then this module will not compile.
 */
object ReceiptScanner {
    /**
     * Load the OCR session from a directory holding the three PP-OCRv5 `.onnx`
     * models (filenames are fixed on the Rust side).
     *
     * @param useOrientationCls when false, skips the textline-orientation
     *   classifier (~23% faster; misses 180°-flipped lines).
     */
    fun load(modelsDirectory: File, useOrientationCls: Boolean = true): OcrSession {
        return OcrSession(modelsDirectory.absolutePath, useOrientationCls)
    }

    /**
     * Scan encoded image bytes (JPEG/PNG) using the current local date for date
     * inference / the placeholder date.
     *
     * UniFFI 0.28 maps Rust `Vec<u8>` → Kotlin `ByteArray` for this crate.
     */
    fun scan(
        session: OcrSession,
        imageData: ByteArray,
        creditCardAccount: String,
    ): ReceiptResult {
        val today = LocalDate.now()
        val date = DateYmd(
            year = today.year,
            month = today.monthValue.toUInt(),
            day = today.dayOfMonth.toUInt(),
        )
        return session.scan(imageData, date, creditCardAccount)
    }
}
