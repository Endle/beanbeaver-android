package com.zhenbo.beanbeaver

import android.app.Activity
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Headless launch-latency probe for the real-device debug-vs-release comparison.
 * Kotlin twin of iOS `LaunchTiming`. Inert unless the app is launched with
 * `--ez logLaunchTiming true`, so it costs nothing in a normal build.
 *
 * It measures **process start → first frame committed**: the system records when
 * the process was forked (before `Application.onCreate`), so the delta captures
 * the whole pre-`onCreate` window — the zygote fork, class loading, and the
 * static initializers of the statically-linked ONNX runtime — which is exactly
 * the span the launch screen is on screen.
 *
 * Each launch appends one record to `filesDir/launch_timing.json`; a host script
 * pulls it with `adb` (mirroring `BatchRunner`'s `batch_out.json`) and reports the
 * distribution across cold launches.
 */
object LaunchTiming {
    private const val TAG = "LaunchTiming"
    const val EXTRA_LOG_LAUNCH_TIMING = "logLaunchTiming"
    private const val FILE_NAME = "launch_timing.json"

    fun isRequested(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_LOG_LAUNCH_TIMING, false) == true

    /**
     * Build configuration this APK was compiled in, so pulled records are
     * self-labeling regardless of which file the host writes them to.
     */
    private val configuration: String
        get() = if (BuildConfig.DEBUG) "debug" else "release"

    /**
     * Milliseconds from process start (fork) to now. Both clocks are
     * `SystemClock.uptimeMillis`, so the subtraction is valid.
     */
    fun millisSinceProcessStart(): Long =
        SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

    /**
     * Record one first-frame measurement. Safe to call unconditionally: no-ops
     * unless the launch asked for it.
     *
     * Hooks the first *committed* frame rather than a `post {}` on the content
     * view — a posted runnable can run before the frame is actually on screen,
     * which would under-report exactly the interval this is meant to measure.
     */
    fun recordFirstFrame(activity: Activity) {
        if (!isRequested(activity.intent)) return
        val window = activity.window ?: return
        val decor = window.decorView

        decor.viewTreeObserver.registerFrameCommitCallback(object : Runnable {
            override fun run() {
                decor.viewTreeObserver.unregisterFrameCommitCallback(this)
                val ms = millisSinceProcessStart()
                Log.i(TAG, "config=$configuration firstFrame=${ms}ms sinceProcStart")
                append(activity, ms)
            }
        })
    }

    private fun append(activity: Activity, ms: Long) {
        runCatching {
            val file = File(activity.filesDir, FILE_NAME)
            val records = if (file.isFile) {
                JSONArray(file.readText())
            } else {
                JSONArray()
            }
            records.put(
                JSONObject()
                    .put("config", configuration)
                    .put("ms", ms)
                    .put("at", System.currentTimeMillis() / 1000.0),
            )
            file.writeText(records.toString(2))
        }.onFailure { Log.w(TAG, "couldn't append the launch record", it) }
    }
}
