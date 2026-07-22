package com.beanbeaver.app.receipt

import uniffi.bb_receipt_ffi.Phase
import uniffi.bb_receipt_ffi.ScanTimings

/**
 * Conveniences over the core's phase-span timings (Kotlin twin of iOS's
 * `ScanTimings` extension). `ScanTimings` is now an ordered `List<PhaseSpan>`
 * over the shared [Phase] taxonomy, so phase names match iOS verbatim.
 */

/** Milliseconds recorded for [phase], or 0 if the span is absent (e.g. parse-only). */
fun ScanTimings.ms(phase: Phase): Double = spans.firstOrNull { it.phase == phase }?.ms ?: 0.0

/** Scan total = sum of all phase spans (there is no separate `total` span). */
val ScanTimings.totalMs: Double get() = spans.sumOf { it.ms }

/** Short row label for a phase, from the shared taxonomy (matches iOS). */
fun Phase.label(): String = when (this) {
    Phase.ACQUIRE -> "acquire"
    Phase.ENCODE -> "encode"
    Phase.DECODE -> "decode"
    Phase.PREP -> "prep"
    Phase.DETECT -> "detect"
    Phase.CLASSIFY -> "classify"
    Phase.RECOGNIZE -> "recognize"
    Phase.PARSE -> "parse"
    Phase.RENDER -> "render"
}
