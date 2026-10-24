package com.ararshot.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun BeforeAfterSlider(
    beforeBitmap: Bitmap,
    afterBitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    var splitFraction by remember { mutableFloatStateOf(0.5f) }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        val totalWidth = maxWidth
        val totalHeight = maxHeight

        // Layer 1: Enhanced / After Image (Full background)
        Image(
            bitmap = afterBitmap.asImageBitmap(),
            contentDescription = "Sesudah Ditingkatkan",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Layer 2: Original / Before Image (Clipped to left side of split fraction)
        val clipShape = remember(splitFraction) {
            object : Shape {
                override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                    val clipWidth = size.width * splitFraction
                    return Outline.Rectangle(Rect(0f, 0f, clipWidth, size.height))
                }
            }
        }

        Image(
            bitmap = beforeBitmap.asImageBitmap(),
            contentDescription = "Sebelum (Asli)",
            modifier = Modifier
                .fillMaxSize()
                .clip(clipShape),
            contentScale = ContentScale.Fit
        )

        // Labels
        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Text(
                text = "SEBELUM (ASLI)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Text(
                text = "SESUDAH (AI 4X)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Draggable Vertical Divider Line
        val dividerX = totalWidth * splitFraction
        Box(
            modifier = Modifier
                .offset { IntOffset(dividerX.toPx().roundToInt() - 1.dp.toPx().roundToInt(), 0) }
                .width(3.dp)
                .fillMaxHeight()
                .background(Color.White)
        )

        // Thumb Handle with Gesture Detector
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = dividerX.toPx().roundToInt() - 20.dp.toPx().roundToInt(),
                        y = (totalHeight.toPx() / 2f).roundToInt() - 20.dp.toPx().roundToInt()
                    )
                }
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newFraction = (splitFraction + (dragAmount.x / size.width.toFloat())).coerceIn(0.02f, 0.98f)
                        splitFraction = newFraction
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Geser Perbandingan",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
