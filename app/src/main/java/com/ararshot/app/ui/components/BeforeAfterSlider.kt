package com.ararshot.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.ui.theme.ArArColors
import kotlin.math.roundToInt

/**
 * Interactive photo canvas:
 * - When [splitCompare] is true: draggable vertical Before/After curtain
 * - When [splitCompare] is false: pinch-to-zoom (1x..5x) + pan + press-and-hold to peek original
 */
@Composable
fun PhotoPreviewCanvas(
    originalBitmap: Bitmap,
    editedBitmap: Bitmap,
    splitCompare: Boolean,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var holdingOriginal by remember { mutableStateOf(false) }

    val origImg = remember(originalBitmap) { originalBitmap.asImageBitmap() }
    val editImg = remember(editedBitmap) { editedBitmap.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center
    ) {
        if (splitCompare && originalBitmap !== editedBitmap) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val w = boxSize.width.toFloat().coerceAtLeast(1f)
                            splitFraction = (splitFraction + dragAmount / w).coerceIn(0.05f, 0.95f)
                        }
                    }
            ) {
                Image(
                    bitmap = editImg,
                    contentDescription = "Sesudah",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                Image(
                    bitmap = origImg,
                    contentDescription = "Asli",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(right = size.width * splitFraction) {
                                this@drawWithContent.drawContent()
                            }
                        }
                )

                // Clean vertical divider + handle
                val dividerX = (boxSize.width * splitFraction).roundToInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(dividerX - 1, 0) }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.9f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(dividerX - 18.dp.roundToPx(), 0) }
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ArArColors.CanvasBg.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CompareArrows,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Minimal corner badges
                CanvasBadge("Asli", Modifier.align(Alignment.TopStart).padding(12.dp))
                CanvasBadge("Edit", Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(originalBitmap, editedBitmap) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (originalBitmap !== editedBitmap && scale <= 1.02f) {
                                val startTime = System.currentTimeMillis()
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1) {
                                        holdingOriginal = false
                                        break
                                    }
                                    val ch = event.changes.firstOrNull() ?: break
                                    if (!ch.pressed) {
                                        holdingOriginal = false
                                        break
                                    }
                                    if ((ch.position - down.position).getDistance() > 18f) {
                                        moved = true
                                        holdingOriginal = false
                                        break
                                    }
                                    if (!moved && System.currentTimeMillis() - startTime > 160L) {
                                        holdingOriginal = true
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale <= 1.01f) {
                                offset = Offset.Zero
                            } else {
                                val maxX = (boxSize.width * (newScale - 1f)) / 2f
                                val maxY = (boxSize.height * (newScale - 1f)) / 2f
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = if (holdingOriginal) origImg else editImg,
                    contentDescription = "Foto",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )

                if (holdingOriginal) {
                    CanvasBadge(
                        text = "Asli",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
