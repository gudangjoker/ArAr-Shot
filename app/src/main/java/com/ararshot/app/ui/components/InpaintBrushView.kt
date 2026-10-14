package com.ararshot.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun InpaintBrushView(
    sourceBitmap: Bitmap,
    maskBitmap: Bitmap,
    brushRadius: Float,
    isErasing: Boolean = false,
    modifier: Modifier = Modifier,
    onMaskUpdated: () -> Unit
) {
    val maskCanvas = remember(maskBitmap) { AndroidCanvas(maskBitmap) }
    val drawPaint = remember(brushRadius, isErasing) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = if (isErasing) AndroidColor.BLACK else AndroidColor.WHITE
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            strokeWidth = brushRadius * 2f
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(brushRadius, isErasing, sourceBitmap, maskBitmap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val scaleX = maskBitmap.width.toFloat() / size.width.toFloat()
                            val scaleY = maskBitmap.height.toFloat() / size.height.toFloat()
                            val bx = offset.x * scaleX
                            val by = offset.y * scaleY

                            maskCanvas.drawCircle(bx, by, brushRadius, drawPaint.apply { style = AndroidPaint.Style.FILL })
                            drawPaint.style = AndroidPaint.Style.STROKE
                            onMaskUpdated()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val scaleX = maskBitmap.width.toFloat() / size.width.toFloat()
                            val scaleY = maskBitmap.height.toFloat() / size.height.toFloat()

                            val prevX = (change.position.x - dragAmount.x) * scaleX
                            val prevY = (change.position.y - dragAmount.y) * scaleY
                            val currX = change.position.x * scaleX
                            val currY = change.position.y * scaleY

                            maskCanvas.drawLine(prevX, prevY, currX, currY, drawPaint)
                            onMaskUpdated()
                        }
                    )
                }
        ) {
            // Draw underlying original image
            drawImage(
                image = sourceBitmap.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // Draw translucent red/magenta mask overlay
            drawImage(
                image = maskBitmap.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = 0.55f,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                    Color(0xFFFF1744),
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop
                )
            )
        }
    }
}
