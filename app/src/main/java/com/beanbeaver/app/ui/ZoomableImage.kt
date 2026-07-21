package com.beanbeaver.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen review of the exact photo the OCR saw, so a user can verify a
 * scan against the original receipt — pinch or double-tap to zoom into fine
 * print. The Android twin of iOS `OriginReceiptView` + `ZoomableImageView`.
 *
 * The JPEG is decoded off the main thread so opening the screen never hitches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginReceiptScreen(
    imageData: ByteArray,
    onBack: () -> Unit,
) {
    // Decode once, off the main thread; identity of the byte array keys it.
    BackHandler(onBack = onBack)

    val decode by produceState<DecodeState>(DecodeState.Loading, imageData) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                ?.asImageBitmap()
                ?.let { DecodeState.Ready(it) }
                ?: DecodeState.Failed
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Original Receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val d = decode) {
                is DecodeState.Loading -> CircularProgressIndicator()
                is DecodeState.Ready -> ZoomableImage(d.bitmap, Modifier.fillMaxSize())
                is DecodeState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "No photo available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private sealed interface DecodeState {
    data object Loading : DecodeState
    data class Ready(val bitmap: ImageBitmap) : DecodeState
    data object Failed : DecodeState
}

/** How far past "fit to screen" a pinch or double-tap can push (6×) — enough to
 * read the smallest receipt print without dissolving into blur. Matches iOS. */
private const val MAX_ZOOM = 6f

/**
 * A pinch-, double-tap-, and drag-to-zoom image viewer.
 *
 * Where iOS leans on `UIScrollView` for the native zoom stack, Compose has no
 * zooming scroll container, so we drive a `graphicsLayer` from raw gestures.
 * The recipe mirrors the UIKit one: the image is drawn at "fit to viewport"
 * (`ContentScale.Fit`, centred), `zoom == 1` is that fit scale, and we clamp the
 * pan so the scaled image can never be dragged past its own edges.
 */
@Composable
fun ZoomableImage(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        // The size the fitted image actually occupies (letterboxed inside the
        // viewport). Panning is clamped against this, not the viewport, so the
        // image can't be dragged into the letterbox margin.
        val fit = min(viewportW / bitmap.width, viewportH / bitmap.height)
        val fittedW = bitmap.width * fit
        val fittedH = bitmap.height * fit

        // `zoom == 1` is fit-to-viewport; `offset` is the post-scale translation
        // in screen pixels. Reset when the image changes.
        var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
        var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

        // The farthest the image may pan at a given zoom: half the overhang of
        // the scaled image beyond the viewport, per axis (0 when it fits).
        fun clamp(o: Offset, z: Float): Offset {
            val maxX = max(0f, (fittedW * z - viewportW) / 2f)
            val maxY = max(0f, (fittedH * z - viewportH) / 2f)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        // Re-anchor the offset so the content point under `focus` (a screen
        // point relative to the box centre) stays put as zoom goes old → new.
        fun zoomAround(focus: Offset, oldZoom: Float, newZoom: Float): Offset =
            focus - (focus - offset) * (newZoom / oldZoom)

        Image(
            bitmap = bitmap,
            contentDescription = "Receipt photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val newZoom = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                        val focus = centroid - Offset(viewportW / 2f, viewportH / 2f)
                        offset = clamp(zoomAround(focus, zoom, newZoom) + pan, newZoom)
                        zoom = newZoom
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (zoom > 1f) {
                                zoom = 1f
                                offset = Offset.Zero
                            } else {
                                val target = min(MAX_ZOOM, 3f)
                                val focus = tap - Offset(viewportW / 2f, viewportH / 2f)
                                offset = clamp(zoomAround(focus, zoom, target), target)
                                zoom = target
                            }
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
