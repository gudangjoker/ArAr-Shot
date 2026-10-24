package com.ararshot.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun InpaintBrushView(
    sourceBitmap: Bitmap,
    maskBitmap: Bitmap,
    brushRadius: Float,
    isErasing: Boolean = false,
    modifier: Modifier = Modifier,
    onMaskUpdated: () -> Unit
) {
    var redrawTick by remember { mutableIntStateOf(0) }
    val maskCanvas = remember(maskBitmap) { AndroidCanvas(maskBitmap) }
    val drawPaint = remember(brushRadius, isErasing) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            if (isErasing) {
                color = AndroidColor.TRANSPARENT
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else {
                color = AndroidColor.WHITE
                xfermode = null
            }
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
                            val fitScale = min(
                                size.width / sourceBitmap.width.toFloat(),
                                size.height / sourceBitmap.height.toFloat()
                            )
                            val drawW = sourceBitmap.width * fitScale
                            val drawH = sourceBitmap.height * fitScale
                            val left = (size.width - drawW) * 0.5f
                            val top = (size.height - drawH) * 0.5f

                            if (drawW > 0f && drawH > 0f) {
                                val bx = ((offset.x - left) / drawW) * maskBitmap.width
                                val by = ((offset.y - top) / drawH) * maskBitmap.height

                                drawPaint.style = AndroidPaint.Style.FILL
                                maskCanvas.drawCircle(bx, by, brushRadius, drawPaint)
                                drawPaint.style = AndroidPaint.Style.STROKE
                                redrawTick++
                                onMaskUpdated()
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val fitScale = min(
                                size.width / sourceBitmap.width.toFloat(),
                                size.height / sourceBitmap.height.toFloat()
                            )
                            val drawW = sourceBitmap.width * fitScale
                            val drawH = sourceBitmap.height * fitScale
                            val left = (size.width - drawW) * 0.5f
                            val top = (size.height - drawH) * 0.5f

                            if (drawW > 0f && drawH > 0f) {
                                val prevX = (((change.position.x - dragAmount.x) - left) / drawW) * maskBitmap.width
                                val prevY = (((change.position.y - dragAmount.y) - top) / drawH) * maskBitmap.height
                                val currX = ((change.position.x - left) / drawW) * maskBitmap.width
                                val currY = ((change.position.y - top) / drawH) * maskBitmap.height

                                maskCanvas.drawLine(prevX, prevY, currX, currY, drawPaint)
                                redrawTick++
                                onMaskUpdated()
                            }
                        }
                    )
                }
        ) {
            // Read redrawTick to ensure immediate Canvas invalidation on brush strokes
            val tick = redrawTick
            if (tick >= 0) {
                val fitScale = min(
                    size.width / sourceBitmap.width.toFloat(),
                    size.height / sourceBitmap.height.toFloat()
                )
                val drawW = (sourceBitmap.width * fitScale).roundToInt().coerceAtLeast(1)
                val drawH = (sourceBitmap.height * fitScale).roundToInt().coerceAtLeast(1)
                val left = ((size.width - drawW) * 0.5f).roundToInt()
                val top = ((size.height - drawH) * 0.5f).roundToInt()

                val dstOffset = IntOffset(left, top)
                val dstSize = IntSize(drawW, drawH)

                // Draw underlying aspect-preserved image
                drawImage(
                    image = sourceBitmap.asImageBitmap(),
                    dstOffset = dstOffset,
                    dstSize = dstSize
                )

                // Draw translucent red/magenta mask overlay aligned 1:1 with sourceBitmap
                drawImage(
                    image = maskBitmap.asImageBitmap(),
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = 0.55f,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        Color(0xFFFF1744),
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop
                    )
                )
            }
        }
    }
}
