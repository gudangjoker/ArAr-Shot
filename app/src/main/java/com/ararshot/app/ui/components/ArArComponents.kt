package com.ararshot.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.ui.theme.ArArColors
import kotlin.math.abs

/**
 * Tactile precision slider with:
 * - Double-tap on label/track to reset to defaultValue
 * - Subtle haptic tick when crossing defaultValue
 * - Optional gradient track (e.g. Temperature blue->amber, Tint green->magenta)
 * - Clean drag start/end hooks for single-step Undo recording
 */
@Composable
fun PrecisionSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    defaultValue: Float = 0f,
    valueFormatter: (Float) -> String = { v ->
        val pct = (v * 100f).toInt()
        when {
            pct > 0 -> "+$pct"
            pct < 0 -> "$pct"
            else -> "0"
        }
    },
    trackColors: List<Color>? = null,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val currentValue by rememberUpdatedState(value)
    val onChangeRef by rememberUpdatedState(onValueChange)
    val onStartRef by rememberUpdatedState(onDragStart)
    val onEndRef by rememberUpdatedState(onDragEnd)

    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
    val normVal = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val normDef = ((defaultValue - valueRange.start) / span).coerceIn(0f, 1f)
    val isModified = abs(value - defaultValue) > span * 0.005f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(defaultValue) {
                    detectTapGestures(
                        onDoubleTap = {
                            onStartRef()
                            onChangeRef(defaultValue)
                            onEndRef()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isModified || isDragging) ArArColors.TextPrimary else ArArColors.TextSecondary,
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.labelMedium,
                color = if (isModified) ArArColors.AccentCyan else ArArColors.TextMuted,
                fontWeight = FontWeight.SemiBold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(valueRange, defaultValue) {
                    detectTapGestures(
                        onTap = { offset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val p = (offset.x / w).coerceIn(0f, 1f)
                            val target = valueRange.start + p * span
                            onStartRef()
                            onChangeRef(target)
                            onEndRef()
                        },
                        onDoubleTap = {
                            onStartRef()
                            onChangeRef(defaultValue)
                            onEndRef()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }
                .pointerInput(valueRange, defaultValue) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                            onStartRef()
                        },
                        onDragEnd = {
                            isDragging = false
                            onEndRef()
                        },
                        onDragCancel = {
                            isDragging = false
                            onEndRef()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = (dragAmount / trackWidthPx.coerceAtLeast(1f)) * span
                            val prev = currentValue
                            var next = (prev + delta).coerceIn(valueRange.start, valueRange.endInclusive)
                            // Gentle magnetic snap near default
                            val snapEps = span * 0.012f
                            if ((prev < defaultValue && next >= defaultValue) ||
                                (prev > defaultValue && next <= defaultValue)
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            if (abs(next - defaultValue) < snapEps && abs(delta) < snapEps * 1.5f) {
                                next = defaultValue
                            }
                            onChangeRef(next)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                trackWidthPx = size.width.coerceAtLeast(1f)
                val cy = size.height / 2f
                val pad = 9.dp.toPx()
                val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                val startX = pad
                val endX = pad + usableW
                val thumbX = pad + normVal * usableW
                val defX = pad + normDef * usableW

                // Base track
                if (trackColors != null && trackColors.size >= 2) {
                    drawLine(
                        brush = Brush.horizontalGradient(trackColors, startX = startX, endX = endX),
                        start = Offset(startX, cy),
                        end = Offset(endX, cy),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                } else {
                    drawLine(
                        color = ArArColors.Surface3,
                        start = Offset(startX, cy),
                        end = Offset(endX, cy),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    // Active fill from defaultValue to thumb
                    drawLine(
                        color = if (isModified) ArArColors.TextPrimary else ArArColors.TextMuted,
                        start = Offset(defX, cy),
                        end = Offset(thumbX, cy),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Default notch marker
                drawCircle(
                    color = ArArColors.TextMuted,
                    radius = 2.dp.toPx(),
                    center = Offset(defX, cy)
                )

                // Crisp ring thumb
                val thumbR = if (isDragging) 9.dp.toPx() else 7.5.dp.toPx()
                drawCircle(
                    color = ArArColors.Surface1,
                    radius = thumbR,
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = if (isDragging) ArArColors.AccentCyan else ArArColors.TextPrimary,
                    radius = thumbR,
                    center = Offset(thumbX, cy),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun SelectablePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) ArArColors.TextPrimary else ArArColors.Surface2
    val fg = if (selected) ArArColors.CanvasBg else ArArColors.TextSecondary
    val borderColor = if (selected) Color.Transparent else ArArColors.Border

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(100))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(100))
            .background(ArArColors.BrandGradient)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = text,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun ColorSwatchDot(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) ArArColors.AccentCyan else ArArColors.Border,
                shape = CircleShape
            )
            .padding(3.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
    )
}
