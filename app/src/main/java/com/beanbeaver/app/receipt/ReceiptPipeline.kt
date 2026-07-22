package com.beanbeaver.app.receipt

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beanbeaver.app.debug.DebugInfoStore
import com.beanbeaver.bbreceiptkit.ReceiptScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bb_receipt_ffi.OcrSession
import uniffi.bb_receipt_ffi.ReceiptResult
import kotlin.coroutines.coroutineContext

sealed interface ScanStatus {
    data object Idle : ScanStatus
    data object Scanning : ScanStatus
    data class Done(val result: ReceiptResult, val wallMs: Double) : ScanStatus
    data class Failed(val message: String) : ScanStatus
}

/**
 * Process-wide OCR session (models are large; load once). Mirrors iOS
 * `OcrSessionProvider` + `ReceiptPipeline`.
 */
class ReceiptPipeline(app: Application) : AndroidViewModel(app) {
    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _scanProgress = MutableStateFlow(0.0)
    val scanProgress: StateFlow<Double> = _scanProgress.asStateFlow()

    private val _scanStepLabel = MutableStateFlow(StepEstimate.FIRST_LABEL)
    val scanStepLabel: StateFlow<String> = _scanStepLabel.asStateFlow()

    /**
     * The exact JPEG bytes the OCR saw, kept so the result screen can show the
     * original receipt for review (see [ScanStatus.Done] and the zoomable
     * viewer). Mirrors iOS `ReceiptPipeline.capturedImageURL`; we hold the bytes
     * in memory rather than on disk since this MVP scans one receipt at a time.
     */
    private val _capturedImage = MutableStateFlow<ByteArray?>(null)
    val capturedImage: StateFlow<ByteArray?> = _capturedImage.asStateFlow()

    var creditCardAccount: String = "Liabilities:CreditCard"

    /**
     * Whether to skip the textline-orientation classifier (~22% of scan time on
     * this device). Backed by SharedPreferences and read by [session]; toggling
     * it forces the next scan to reload the OCR session with/without the cls
     * model. Fine for upright receipts; hurts 180°-rotated lines.
     */
    private val _skipOrientation = MutableStateFlow(prefsSkipOrientation(app))
    val skipOrientation: StateFlow<Boolean> = _skipOrientation.asStateFlow()

    fun setSkipOrientation(value: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SKIP_ORIENTATION, value)
            .apply()
        _skipOrientation.value = value
    }

    private var progressJob: Job? = null

    fun reset() {
        progressJob?.cancel()
        _status.value = ScanStatus.Idle
        _scanProgress.value = 0.0
        _scanStepLabel.value = StepEstimate.FIRST_LABEL
        _capturedImage.value = null
    }

    fun scanBundledSample() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val bytes = withContext(Dispatchers.IO) {
                ctx.assets.open("samples/costco_20260301_redact.jpg").use { it.readBytes() }
            }
            scan(bytes)
        }
    }

    fun scan(imageData: ByteArray) {
        viewModelScope.launch {
            _status.value = ScanStatus.Scanning
            _capturedImage.value = imageData
            _scanProgress.value = 0.0
            _scanStepLabel.value = StepEstimate.FIRST_LABEL
            progressJob?.cancel()
            progressJob = launch { animateEstimatedProgress(skipOrientation.value) }

            val account = creditCardAccount
            val app = getApplication<Application>()
            val currency = LedgerFormatPrefs.currency(app)
            val taxAccount = LedgerFormatPrefs.taxAccount(app)
            val started = System.nanoTime()
            try {
                val result = withContext(Dispatchers.Default) {
                    val session = session(app)
                    ReceiptScanner.scan(session, imageData, account, currency, taxAccount)
                }
                val wallMs = (System.nanoTime() - started) / 1_000_000.0
                progressJob?.cancel()
                _scanProgress.value = 1.0
                rememberScanDuration(wallMs)
                logTimings(result, wallMs)
                DebugInfoStore.recordSuccess(app, result, wallMs)
                _status.value = ScanStatus.Done(result, wallMs)
            } catch (t: Throwable) {
                progressJob?.cancel()
                Log.e(TAG, "scan failed", t)
                DebugInfoStore.recordFailure(app, t)
                _status.value = ScanStatus.Failed(t.message ?: t.toString())
            }
        }
    }

    /**
     * Emit the per-phase scan timings to logcat so an interactive scan (photo
     * picker or "run bundled sample") is as greppable as the headless
     * [BatchRunner] path. Same Phase taxonomy the result-screen TimingBreakdown
     * renders; overhead = wall − Rust total (JNI + first-scan model load).
     */
    private fun logTimings(result: ReceiptResult, wallMs: Double) {
        val t = result.timings
        val phases = t.spans.joinToString(" ") { "${it.phase.label()}=%.0f".format(it.ms) }
        val overheadMs = (wallMs - t.totalMs).coerceAtLeast(0.0)
        Log.i(
            TAG,
            "scan ok merchant=${result.merchant} total=${result.total} " +
                "wallMs=%.0f rustTotalMs=%.0f overheadMs=%.0f | %s"
                    .format(wallMs, t.totalMs, overheadMs, phases),
        )
    }

    /**
     * Animate `scanProgress`/`scanStepLabel` against an estimate, since the FFI
     * gives no live progress signal. The total is [estimatedTotalMs] — the last
     * real scan's wall time — so the bar tracks *this device* instead of a stale
     * constant (the old fixed 816 ms made it slam to the 0.96 cap in ~1 s and then
     * freeze through the multi-second recognize step). [StepEstimate] only supplies
     * the phase *shape*; recognize is ~half, so the bar keeps moving through it.
     */
    private suspend fun animateEstimatedProgress(skipOrientation: Boolean) {
        val steps = StepEstimate.steps(skipOrientation)
        val total = estimatedTotalMs()
        val cumulative = StepEstimate.cumulativeMs(steps, total)
        val started = System.nanoTime()
        while (coroutineContext.isActive) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            val stepIndex = cumulative
                .indexOfFirst { elapsedMs < it }
                .let { if (it < 0) steps.lastIndex else it }
            _scanStepLabel.value = steps[stepIndex].label
            _scanProgress.value = (elapsedMs / total).coerceAtMost(0.96)
            delay(80)
        }
    }

    /** Last real scan's wall time (persisted), clamped to a sane range; the seed
     *  for the next scan's progress estimate. Defaults before the first scan. */
    private fun estimatedTotalMs(): Double =
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_LAST_SCAN_MS, StepEstimate.DEFAULT_TOTAL_MS.toFloat())
            .toDouble()
            .coerceIn(StepEstimate.MIN_TOTAL_MS, StepEstimate.MAX_TOTAL_MS)

    private fun rememberScanDuration(wallMs: Double) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LAST_SCAN_MS, wallMs.toFloat())
            .apply()
    }

    /**
     * The *shape* of a scan — relative per-phase weights, not absolute times. The
     * absolute total comes from [estimatedTotalMs] (last real scan), so only the
     * proportions live here. Weights are the measured release split on the Exynos
     * 1380 (2026-07-21): decode+prep ~380, detect ~600, classify ~1450,
     * recognize ~3450, parse ~780 ms.
     */
    private object StepEstimate {
        data class Step(val label: String, val weight: Double)

        const val FIRST_LABEL = "Preparing image…"
        const val DEFAULT_TOTAL_MS = 6500.0
        const val MIN_TOTAL_MS = 1500.0
        const val MAX_TOTAL_MS = 20000.0

        private val prep = Step(FIRST_LABEL, 380.0)
        private val detect = Step("Detecting text…", 600.0)
        private val orient = Step("Checking orientation…", 1450.0)
        private val recognize = Step("Recognizing text…", 3450.0)
        private val parse = Step("Parsing receipt…", 780.0)

        /** Phases in order; the orientation classifier is dropped when skipped. */
        fun steps(skipOrientation: Boolean): List<Step> =
            if (skipOrientation) listOf(prep, detect, recognize, parse)
            else listOf(prep, detect, orient, recognize, parse)

        /** Label-boundary times: the weights scaled to fill [totalMs]. */
        fun cumulativeMs(steps: List<Step>, totalMs: Double): List<Double> {
            val sum = steps.sumOf { it.weight }
            return steps.runningFold(0.0) { acc, s -> acc + s.weight }
                .drop(1)
                .map { it / sum * totalMs }
        }
    }

    companion object {
        private const val TAG = "ReceiptPipeline"
        private const val PREFS_NAME = "beanbeaver"
        private const val KEY_SKIP_ORIENTATION = "skipOrientationCheck"
        private const val KEY_LAST_SCAN_MS = "lastScanWallMs"

        @Volatile
        private var cached: OcrSession? = null

        @Volatile
        private var loadedWithOrientationCls: Boolean? = null

        private fun session(context: Context): OcrSession {
            val useCls = !prefsSkipOrientation(context)
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

        private fun prefsSkipOrientation(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SKIP_ORIENTATION, false)
    }
}
