package com.zhenbo.beanbeaver.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import de.schliweb.makeacopy.ml.corners.DetectionResult
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Turning DocQuadNet's four corners into a cropped, deskewed bitmap.
 *
 * The warp is `Matrix.setPolyToPoly`, which is a genuine 4-point perspective
 * transform — the platform gives us for free the one piece of OpenCV we would
 * otherwise have needed. That is why no OpenCV enters this flavour: the model
 * finds the paper, and Android straightens it.
 */

/** Corner order used throughout: top-left, top-right, bottom-right, bottom-left. */
private const val TL = 0
private const val TR = 1
private const val BR = 2
private const val BL = 3

/**
 * Crop [src] to the detected quad, correcting perspective.
 *
 * Returns null when there is nothing worth doing — no detection, or a quad so
 * close to the full frame that warping would only cost a resample. Callers keep
 * the original in that case; a receipt photographed straight-on is a normal
 * outcome, not a failure.
 */
fun cropToQuad(src: Bitmap, detection: DetectionResult?): Bitmap? {
    val quad = detection?.takeIf { it.success }?.cornersOriginalTLTRBRBL ?: return null
    if (quad.size != 4) return null

    // Output size from the quad's own edges: average the two opposing sides so a
    // tilted shot doesn't inherit whichever edge happened to be nearer the lens.
    val widthTop = dist(quad[TL], quad[TR])
    val widthBottom = dist(quad[BL], quad[BR])
    val heightLeft = dist(quad[TL], quad[BL])
    val heightRight = dist(quad[TR], quad[BR])
    val outW = ((widthTop + widthBottom) / 2.0).roundToInt().coerceAtLeast(1)
    val outH = ((heightLeft + heightRight) / 2.0).roundToInt().coerceAtLeast(1)

    if (isEssentiallyWholeFrame(quad, src.width, src.height, outW, outH)) return null

    val srcPts = floatArrayOf(
        quad[TL][0].toFloat(), quad[TL][1].toFloat(),
        quad[TR][0].toFloat(), quad[TR][1].toFloat(),
        quad[BR][0].toFloat(), quad[BR][1].toFloat(),
        quad[BL][0].toFloat(), quad[BL][1].toFloat(),
    )
    val dstPts = floatArrayOf(
        0f, 0f,
        outW.toFloat(), 0f,
        outW.toFloat(), outH.toFloat(),
        0f, outH.toFloat(),
    )

    val matrix = Matrix()
    if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return null

    return runCatching {
        Bitmap.createBitmap(outW, outH, src.config ?: Bitmap.Config.ARGB_8888).also { out ->
            android.graphics.Canvas(out).drawBitmap(
                src,
                matrix,
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
        }
    }.getOrNull()
}

/**
 * A quad covering nearly the whole frame means the model found the photo's
 * borders rather than the receipt's, or the receipt genuinely fills the shot.
 * Either way, resampling buys nothing and costs sharpness the OCR wants.
 */
private fun isEssentiallyWholeFrame(
    quad: Array<DoubleArray>,
    srcW: Int,
    srcH: Int,
    outW: Int,
    outH: Int,
): Boolean {
    if (srcW <= 0 || srcH <= 0) return true
    val areaRatio = (outW.toDouble() * outH) / (srcW.toDouble() * srcH)
    if (areaRatio < 0.98) return false
    // Still worth warping if it is full-frame but visibly skewed.
    val skew = maxOf(
        kotlin.math.abs(quad[TL][1] - quad[TR][1]),
        kotlin.math.abs(quad[BL][1] - quad[BR][1]),
        kotlin.math.abs(quad[TL][0] - quad[BL][0]),
        kotlin.math.abs(quad[TR][0] - quad[BR][0]),
    )
    return skew < 0.01 * maxOf(srcW, srcH)
}

private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])
