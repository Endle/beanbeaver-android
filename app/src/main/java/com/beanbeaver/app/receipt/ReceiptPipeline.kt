package com.beanbeaver.app.receipt

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val _scanStepLabel = MutableStateFlow(StepEstimate.steps.first().label)
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

    private var progressJob: Job? = null

    fun reset() {
        progressJob?.cancel()
        _status.value = ScanStatus.Idle
        _scanProgress.value = 0.0
        _scanStepLabel.value = StepEstimate.steps.first().label
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
            _scanStepLabel.value = StepEstimate.steps.first().label
            progressJob?.cancel()
            progressJob = launch { animateEstimatedProgress() }

            val account = creditCardAccount
            val started = System.nanoTime()
            try {
                val result = withContext(Dispatchers.Default) {
                    val session = session(getApplication())
                    ReceiptScanner.scan(session, imageData, account)
                }
                val wallMs = (System.nanoTime() - started) / 1_000_000.0
                progressJob?.cancel()
                _scanProgress.value = 1.0
                logTimings(result, wallMs)
                _status.value = ScanStatus.Done(result, wallMs)
            } catch (t: Throwable) {
                progressJob?.cancel()
                Log.e(TAG, "scan failed", t)
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

    private suspend fun animateEstimatedProgress() {
        val started = System.nanoTime()
        while (coroutineContext.isActive) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            val stepIndex = StepEstimate.cumulativeMs
                .indexOfFirst { elapsedMs < it }
                .let { if (it < 0) StepEstimate.steps.lastIndex else it }
            _scanStepLabel.value = StepEstimate.steps[stepIndex].label
            _scanProgress.value = (elapsedMs / StepEstimate.totalMs).coerceAtMost(0.96)
            delay(80)
        }
    }

    private object StepEstimate {
        data class Step(val label: String, val ms: Double)

        val steps = listOf(
            Step("Preparing image…", 28.0),
            Step("Detecting text…", 322.0),
            Step("Checking orientation…", 41.0),
            Step("Recognizing text…", 408.0),
            Step("Parsing receipt…", 17.0),
        )
        val cumulativeMs: List<Double> = steps.runningFold(0.0) { acc, s -> acc + s.ms }.drop(1)
        val totalMs: Double = cumulativeMs.last()
    }

    companion object {
        private const val TAG = "ReceiptPipeline"

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
            context.getSharedPreferences("beanbeaver", Context.MODE_PRIVATE)
                .getBoolean("skipOrientationCheck", false)
    }
}
