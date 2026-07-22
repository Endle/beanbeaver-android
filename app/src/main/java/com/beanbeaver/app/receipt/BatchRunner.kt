package com.beanbeaver.app.receipt

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.beanbeaver.bbreceiptkit.ReceiptScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.bb_receipt_ffi.OcrSession
import uniffi.bb_receipt_ffi.Phase
import java.io.File

/**
 * Headless on-device E2E harness — Android twin of iOS BatchRunner in
 * ReceiptPipeline.swift.
 *
 * Host script (scripts/android-e2e.sh) pushes JPEG fixtures into the app
 * batch_in directory, launches with intent extra autoRunBatch=true, waits for
 * batch_out.json, then runs compare-e2e.py.
 */
object BatchRunner {
    private const val TAG = "BatchRunner"
    const val EXTRA_AUTO_RUN_BATCH = "autoRunBatch"
    const val EXTRA_BATCH_DELAY_SEC = "batchDelaySec"

    fun delaySec(context: Context): Double {
        val extras = (context.applicationContext as? BatchLaunchHolder)?.batchExtras
        return extras?.getDouble(EXTRA_BATCH_DELAY_SEC, 0.0) ?: 0.0
    }

    /** Preferred dirs: external app files (adb-pushable) then internal files. */
    fun batchInDirs(context: Context): List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(File(it, "batch_in")) }
        add(File(context.filesDir, "batch_in"))
    }

    fun batchOutFile(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) {
            File(external, "batch_out.json")
        } else {
            File(context.filesDir, "batch_out.json")
        }
    }

    /**
     * Load OCR once, scan every batch_in JPEG (sorted), write batch_out.json
     * atomically. JSON shape matches iOS BatchRunner so compare-e2e.py works
     * unchanged.
     */
    suspend fun runBatch(context: Context) {
        withContext(Dispatchers.Default) {
            val outFile = batchOutFile(context)
            outFile.delete()

            val images = batchInDirs(context)
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                        .orEmpty()
                }
                .distinctBy { it.name }
                .sortedBy { it.name }

            val delaySec = delaySec(context)
            Log.i(TAG, "${images.size} image(s), delay=${delaySec}s out=${outFile.absolutePath}")

            val modelsDir = ModelStore.ensureModels(context)
            val session: OcrSession = try {
                ReceiptScanner.load(modelsDir, useOrientationCls = true)
            } catch (t: Throwable) {
                Log.e(TAG, "model load failed", t)
                writeOutput(
                    outFile,
                    images.map { failure(it.nameWithoutExtension, t.message ?: t.toString()) },
                )
                return@withContext
            }

            val results = ArrayList<JSONObject>(images.size)
            for ((i, file) in images.withIndex()) {
                val name = file.nameWithoutExtension
                if (delaySec > 0 && i > 0) {
                    delay((delaySec * 1000).toLong())
                }
                val bytes = try {
                    file.readBytes()
                } catch (t: Throwable) {
                    results.add(failure(name, "load failed: ${t.message}"))
                    continue
                }
                val started = System.nanoTime()
                try {
                    val r = ReceiptScanner.scan(session, bytes, "Liabilities:CreditCard")
                    val wallMs = (System.nanoTime() - started) / 1_000_000.0
                    results.add(
                        JSONObject()
                            .put("name", name)
                            .put("merchant", r.merchant)
                            .put("date", r.date ?: JSONObject.NULL)
                            .put("dateIsPlaceholder", r.dateIsPlaceholder)
                            .put("total", r.total)
                            .put("subtotal", r.subtotal ?: JSONObject.NULL)
                            .put("tax", r.tax ?: JSONObject.NULL)
                            .put(
                                "items",
                                JSONArray().also { arr ->
                                    for (item in r.items) {
                                        arr.put(
                                            JSONObject()
                                                .put("description", item.description)
                                                .put("price", item.price)
                                                .put("category", item.category ?: JSONObject.NULL),
                                        )
                                    }
                                },
                            )
                            .put("warnings", JSONArray(r.warnings))
                            .put("wallMs", wallMs)
                            .put(
                                "timings",
                                JSONObject()
                                    .put("decodeMs", r.timings.ms(Phase.DECODE))
                                    .put("prepMs", r.timings.ms(Phase.PREP))
                                    .put("detectMs", r.timings.ms(Phase.DETECT))
                                    .put("classifyMs", r.timings.ms(Phase.CLASSIFY))
                                    .put("recognizeMs", r.timings.ms(Phase.RECOGNIZE))
                                    .put("parseMs", r.timings.ms(Phase.PARSE))
                                    .put("totalMs", r.timings.totalMs),
                            )
                            .put("error", JSONObject.NULL),
                    )
                    Log.i(TAG, "ok $name wallMs=$wallMs merchant=${r.merchant} total=${r.total}")
                } catch (t: Throwable) {
                    Log.e(TAG, "scan failed $name", t)
                    results.add(failure(name, t.message ?: t.toString()))
                }
            }

            writeOutput(outFile, results)
            Log.i(TAG, "wrote ${results.size} result(s) to ${outFile.absolutePath}")
        }
    }

    private fun failure(name: String, message: String): JSONObject =
        JSONObject()
            .put("name", name)
            .put("merchant", "")
            .put("date", JSONObject.NULL)
            .put("dateIsPlaceholder", false)
            .put("total", "")
            .put("subtotal", JSONObject.NULL)
            .put("tax", JSONObject.NULL)
            .put("items", JSONArray())
            .put("warnings", JSONArray())
            .put("wallMs", 0.0)
            .put("timings", JSONObject.NULL)
            .put("error", message)

    private fun writeOutput(outFile: File, results: List<JSONObject>) {
        val root = JSONObject()
            .put("count", results.size)
            .put("results", JSONArray(results))
        outFile.parentFile?.mkdirs()
        val tmp = File(outFile.absolutePath + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
    }
}

/** Application-level stash for the cold-start intent that requested a batch. */
interface BatchLaunchHolder {
    var batchExtras: Bundle?
}
