package com.ararshot.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun InpaintBrushView(
    baseBitmap: Bitmap,
    maskBitmap: Bitmap,
    brushRadiusPx: Float,
    isErasing: Boolean,
    maskRevision: Int,
    onMaskUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var localStrokeTick by remember { mutableIntStateOf(0) }
    val currentRadius by rememberUpdatedState(brushRadiusPx)
    val currentErasing by rememberUpdatedState(isErasing)
    val currentOnUpdated by rememberUpdatedState(onMaskUpdated)

    val drawPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    fun mapToImage(offset: Offset): Pair<Float, Float>? {
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        if (bw <= 0f || bh <= 0f) return null
        val imgW = baseBitmap.width.toFloat()
        val imgH = baseBitmap.height.toFloat()
        val scale = minOf(bw / imgW, bh / imgH)
        val drawnW = imgW * scale
        val drawnH = imgH * scale
        val left = (bw - drawnW) / 2f
        val top = (bh - drawnH) / 2f
        val ix = (offset.x - left) / scale
        val iy = (offset.y - top) / scale
        if (ix < 0f || ix > imgW || iy < 0f || iy > imgH) return null
        return ix to iy
    }

    fun paintPoint(x: Float, y: Float, prevX: Float?, prevY: Float?) {
        val canvas = Canvas(maskBitmap)
        val scaleFactor = baseBitmap.width.toFloat() / boxSize.width.coerceAtLeast(1).toFloat()
        val r = (currentRadius * scaleFactor).coerceAtLeast(4f)
        if (currentErasing) {
            drawPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            drawPaint.color = android.graphics.Color.TRANSPARENT
        } else {
            drawPaint.xfermode = null
            drawPaint.color = android.graphics.Color.argb(170, 239, 68, 68)
        }
        drawPaint.strokeWidth = r * 2f
        if (prevX != null && prevY != null) {
            canvas.drawLine(prevX, prevY, x, y, drawPaint)
        } else {
            canvas.drawCircle(x, y, r, drawPaint)
        }
        localStrokeTick++
        currentOnUpdated()
    }

    val baseImg = remember(baseBitmap) { baseBitmap.asImageBitmap() }
    val maskImg = remember(maskBitmap, maskRevision, localStrokeTick) { maskBitmap.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(maskBitmap, baseBitmap) {
                detectTapGestures { offset ->
                    mapToImage(offset)?.let { (x, y) -> paintPoint(x, y, null, null) }
                }
            }
            .pointerInput(maskBitmap, baseBitmap) {
                var lastPt: Pair<Float, Float>? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        lastPt = mapToImage(offset)
                        lastPt?.let { (x, y) -> paintPoint(x, y, null, null) }
                    },
                    onDragEnd = { lastPt = null },
                    onDragCancel = { lastPt = null },
                    onDrag = { change, _ ->
                        change.consume()
                        val cur = mapToImage(change.position)
                        if (cur != null) {
                            paintPoint(cur.first, cur.second, lastPt?.first, lastPt?.second)
                            lastPt = cur
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = baseImg,
            contentDescription = "Foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            bitmap = maskImg,
            contentDescription = "Area hapus",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}
