package com.ararshot.app.ui.components

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class CropHandle { NONE, MOVE, TL, TR, BL, BR }

/**
 * Interactive on-canvas crop & straighten overlay with rule-of-thirds grid,
 * corner handles, and optional aspect ratio locking.
 */
@Composable
fun InteractiveCropCanvas(
    bitmap: Bitmap,
    cropRect: RectF,
    straightenDegrees: Float,
    lockedAspectRatio: Float?, // width / height in pixel space, or null for free
    onCropRectChange: (RectF) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val currentRect by rememberUpdatedState(cropRect)
    val onChangeRef by rememberUpdatedState(onCropRectChange)
    val lockedRatioRef by rememberUpdatedState(lockedAspectRatio)

    val img = remember(bitmap) { bitmap.asImageBitmap() }
    val imgAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()

    // Compute displayed image bounds inside boxSize (ContentScale.Fit)
    val imgBounds = remember(boxSize, imgAspect) {
        val bw = boxSize.width.toFloat().coerceAtLeast(1f)
        val bh = boxSize.height.toFloat().coerceAtLeast(1f)
        val boxAspect = bw / bh
        if (imgAspect > boxAspect) {
            val h = bw / imgAspect
            RectF(0f, (bh - h) / 2f, bw, (bh + h) / 2f)
        } else {
            val w = bh * imgAspect
            RectF((bw - w) / 2f, 0f, (bw + w) / 2f, bh)
        }
    }
    val boundsRef by rememberUpdatedState(imgBounds)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center
    ) {
        val zoomScale = remember(straightenDegrees, bitmap.width, bitmap.height) {
            if (abs(straightenDegrees) < 0.05f) 1f else {
                val rad = Math.toRadians(abs(straightenDegrees.toDouble()))
                val w = bitmap.width.toDouble()
                val h = bitmap.height.toDouble()
                val c = kotlin.math.cos(rad)
                val s = kotlin.math.sin(rad)
                max((w * c + h * s) / w, (h * c + w * s) / h).toFloat()
            }
        }

        Image(
            bitmap = img,
            contentDescription = "Potong foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = straightenDegrees
                    scaleX = zoomScale
                    scaleY = zoomScale
                }
        )

        var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val b = boundsRef
                            val r = currentRect
                            val l = b.left + r.left * b.width()
                            val t = b.top + r.top * b.height()
                            val ri = b.left + r.right * b.width()
                            val bo = b.top + r.bottom * b.height()
                            val tol = 36.dp.toPx()

                            activeHandle = when {
                                (start - Offset(l, t)).getDistance() <= tol -> CropHandle.TL
                                (start - Offset(ri, t)).getDistance() <= tol -> CropHandle.TR
                                (start - Offset(l, bo)).getDistance() <= tol -> CropHandle.BL
                                (start - Offset(ri, bo)).getDistance() <= tol -> CropHandle.BR
                                start.x in l..ri && start.y in t..bo -> CropHandle.MOVE
                                else -> CropHandle.NONE
                            }
                        },
                        onDragEnd = { activeHandle = CropHandle.NONE },
                        onDragCancel = { activeHandle = CropHandle.NONE },
                        onDrag = { change, drag ->
                            change.consume()
                            val b = boundsRef
                            val bw = b.width().coerceAtLeast(1f)
                            val bh = b.height().coerceAtLeast(1f)
                            val dx = drag.x / bw
                            val dy = drag.y / bh
                            val r = RectF(currentRect)
                            val minSize = 0.12f
                            val ratio = lockedRatioRef // targetW / targetH in pixels
                            // Convert pixel aspect ratio to normalized (0..1) aspect ratio: normW / normH = ratio / imgAspect
                            val normRatio = ratio?.let { it / imgAspect }

                            when (activeHandle) {
                                CropHandle.MOVE -> {
                                    val w = r.width()
                                    val h = r.height()
                                    val nl = (r.left + dx).coerceIn(0f, 1f - w)
                                    val nt = (r.top + dy).coerceIn(0f, 1f - h)
                                    r.set(nl, nt, nl + w, nt + h)
                                }
                                CropHandle.TL -> {
                                    var nl = (r.left + dx).coerceIn(0f, r.right - minSize)
                                    var nt = (r.top + dy).coerceIn(0f, r.bottom - minSize)
                                    if (normRatio != null) {
                                        val w = r.right - nl
                                        val h = (w / normRatio).coerceIn(minSize, r.bottom)
                                        nt = r.bottom - h
                                        nl = r.right - h * normRatio
                                    }
                                    r.set(nl.coerceAtLeast(0f), nt.coerceAtLeast(0f), r.right, r.bottom)
                                }
                                CropHandle.TR -> {
                                    var nr = (r.right + dx).coerceIn(r.left + minSize, 1f)
                                    var nt = (r.top + dy).coerceIn(0f, r.bottom - minSize)
                                    if (normRatio != null) {
                                        val w = nr - r.left
                                        val h = (w / normRatio).coerceIn(minSize, r.bottom)
                                        nt = r.bottom - h
                                        nr = r.left + h * normRatio
                                    }
                                    r.set(r.left, nt.coerceAtLeast(0f), nr.coerceAtMost(1f), r.bottom)
                                }
                                CropHandle.BL -> {
                                    var nl = (r.left + dx).coerceIn(0f, r.right - minSize)
                                    var nb = (r.bottom + dy).coerceIn(r.top + minSize, 1f)
                                    if (normRatio != null) {
                                        val w = r.right - nl
                                        val h = (w / normRatio).coerceIn(minSize, 1f - r.top)
                                        nb = r.top + h
                                        nl = r.right - h * normRatio
                                    }
                                    r.set(nl.coerceAtLeast(0f), r.top, r.right, nb.coerceAtMost(1f))
                                }
                                CropHandle.BR -> {
                                    var nr = (r.right + dx).coerceIn(r.left + minSize, 1f)
                                    var nb = (r.bottom + dy).coerceIn(r.top + minSize, 1f)
                                    if (normRatio != null) {
                                        val w = nr - r.left
                                        val h = (w / normRatio).coerceIn(minSize, 1f - r.top)
                                        nb = r.top + h
                                        nr = r.left + h * normRatio
                                    }
                                    r.set(r.left, r.top, nr.coerceAtMost(1f), nb.coerceAtMost(1f))
                                }
                                CropHandle.NONE -> return@detectDragGestures
                            }
                            onChangeRef(r)
                        }
                    )
                }
        ) {
            val b = imgBounds
            val l = b.left + cropRect.left * b.width()
            val t = b.top + cropRect.top * b.height()
            val r = b.left + cropRect.right * b.width()
            val bo = b.top + cropRect.bottom * b.height()
            val cw = (r - l).coerceAtLeast(1f)
            val ch = (bo - t).coerceAtLeast(1f)

            // Dim outside crop area
            val scrim = Color.Black.copy(alpha = 0.62f)
            drawRect(scrim, Offset(b.left, b.top), Size(b.width(), (t - b.top).coerceAtLeast(0f)))
            drawRect(scrim, Offset(b.left, bo), Size(b.width(), (b.bottom - bo).coerceAtLeast(0f)))
            drawRect(scrim, Offset(b.left, t), Size((l - b.left).coerceAtLeast(0f), ch))
            drawRect(scrim, Offset(r, t), Size((b.right - r).coerceAtLeast(0f), ch))

            // Rule-of-thirds grid
            val gridColor = Color.White.copy(alpha = 0.35f)
            for (k in 1..2) {
                val gx = l + cw * (k / 3f)
                val gy = t + ch * (k / 3f)
                drawLine(gridColor, Offset(gx, t), Offset(gx, bo), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(l, gy), Offset(r, gy), strokeWidth = 1.dp.toPx())
            }

            // Outer crop border
            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = Size(cw, ch),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Crisp corner brackets
            val arm = min(20.dp.toPx(), min(cw, ch) * 0.35f)
            val strokeW = 3.5.dp.toPx()
            drawLine(Color.White, Offset(l, t), Offset(l + arm, t), strokeW)
            drawLine(Color.White, Offset(l, t), Offset(l, t + arm), strokeW)

            drawLine(Color.White, Offset(r, t), Offset(r - arm, t), strokeW)
            drawLine(Color.White, Offset(r, t), Offset(r, t + arm), strokeW)

            drawLine(Color.White, Offset(l, bo), Offset(l + arm, bo), strokeW)
            drawLine(Color.White, Offset(l, bo), Offset(l, bo - arm), strokeW)

            drawLine(Color.White, Offset(r, bo), Offset(r - arm, bo), strokeW)
            drawLine(Color.White, Offset(r, bo), Offset(r, bo - arm), strokeW)
        }
    }
}

/**
 * Helper to compute a centered normalized crop RectF matching a target pixel aspect ratio.
 */
fun centeredCropRectForAspect(bitmapWidth: Int, bitmapHeight: Int, targetAspectRatio: Float?): RectF {
    if (targetAspectRatio == null || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return RectF(0f, 0f, 1f, 1f)
    }
    val imgAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
    val normW: Float
    val normH: Float
    if (imgAspect > targetAspectRatio) {
        normH = 1f
        normW = (targetAspectRatio / imgAspect).coerceIn(0.1f, 1f)
    } else {
        normW = 1f
        normH = (imgAspect / targetAspectRatio).coerceIn(0.1f, 1f)
    }
    val l = (1f - normW) / 2f
    val t = (1f - normH) / 2f
    return RectF(l, t, l + normW, t + normH)
}
