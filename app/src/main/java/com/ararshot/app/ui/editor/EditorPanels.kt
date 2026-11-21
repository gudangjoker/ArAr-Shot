package com.ararshot.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.BgReplaceConfig
import com.ararshot.app.engine.BgReplaceMode
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TextFontFamily
import com.ararshot.app.engine.TextOverlayConfig
import com.ararshot.app.engine.TextStyleModel
import com.ararshot.app.model.AnimationModelsUiState
import com.ararshot.app.model.AnimationStyle
import com.ararshot.app.model.ColorAdjustments
import com.ararshot.app.model.ExportSizeOption
import com.ararshot.app.model.FilterPreset
import com.ararshot.app.model.HslBand
import com.ararshot.app.ui.components.ColorSwatchDot
import com.ararshot.app.ui.components.PrecisionSlider
import com.ararshot.app.ui.components.PrimaryActionButton
import com.ararshot.app.ui.components.SelectablePill
import com.ararshot.app.ui.theme.ArArColors

// ---------------------------------------------------------------------------
// 1. Adjust Panel (Cahaya, Warna, HSL, Detail + Auto Enhance)
// ---------------------------------------------------------------------------

@Composable
fun AdjustPanel(
    adjustments: ColorAdjustments,
    onUpdate: ((ColorAdjustments) -> ColorAdjustments) -> Unit,
    onAutoEnhance: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onResetSection: () -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) }
    var selectedHslBand by remember { mutableIntStateOf(0) }
    val tabs = listOf("Cahaya", "Warna", "HSL", "Detail")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectablePill(
                label = "Otomatis",
                selected = false,
                icon = Icons.Default.AutoFixHigh,
                onClick = onAutoEnhance
            )
            tabs.forEachIndexed { idx, title ->
                SelectablePill(
                    label = title,
                    selected = subTab == idx,
                    onClick = { subTab = idx }
                )
            }
            if (!adjustments.isIdentity()) {
                Text(
                    text = "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArArColors.TextMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .clickable(onClick = onResetSection)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (subTab) {
                0 -> { // Cahaya
                    PrecisionSlider(
                        label = "Pencahayaan",
                        value = adjustments.exposure,
                        valueRange = -2f..2f,
                        valueFormatter = { v -> if (v >= 0f) "+%.2f EV".format(v) else "%.2f EV".format(v) },
                        onValueChange = { v -> onUpdate { it.copy(exposure = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Kontras",
                        value = adjustments.contrast,
                        onValueChange = { v -> onUpdate { it.copy(contrast = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Sorotan",
                        value = adjustments.highlights,
                        onValueChange = { v -> onUpdate { it.copy(highlights = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Bayangan",
                        value = adjustments.shadows,
                        onValueChange = { v -> onUpdate { it.copy(shadows = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Putih",
                        value = adjustments.whites,
                        onValueChange = { v -> onUpdate { it.copy(whites = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Hitam",
                        value = adjustments.blacks,
                        onValueChange = { v -> onUpdate { it.copy(blacks = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                }
                1 -> { // Warna
                    PrecisionSlider(
                        label = "Suhu",
                        value = adjustments.temp,
                        trackColors = listOf(Color(0xFF3B82F6), Color(0xFF8A93A6), Color(0xFFF59E0B)),
                        onValueChange = { v -> onUpdate { it.copy(temp = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Rona (Tint)",
                        value = adjustments.tint,
                        trackColors = listOf(Color(0xFF22C55E), Color(0xFF8A93A6), Color(0xFFEC4899)),
                        onValueChange = { v -> onUpdate { it.copy(tint = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Vibrance",
                        value = adjustments.vibrance,
                        onValueChange = { v -> onUpdate { it.copy(vibrance = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Saturasi",
                        value = adjustments.saturation,
                        onValueChange = { v -> onUpdate { it.copy(saturation = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                }
                2 -> { // HSL 8-Warna
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        HslBand.entries.forEach { band ->
                            ColorSwatchDot(
                                color = Color(band.colorHex),
                                selected = selectedHslBand == band.index,
                                onClick = { selectedHslBand = band.index }
                            )
                        }
                    }
                    val band = HslBand.entries[selectedHslBand]
                    PrecisionSlider(
                        label = "Rona ${band.label}",
                        value = adjustments.hslHue[selectedHslBand],
                        onValueChange = { v -> onUpdate { it.withHsl(selectedHslBand, hue = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Saturasi ${band.label}",
                        value = adjustments.hslSat[selectedHslBand],
                        onValueChange = { v -> onUpdate { it.withHsl(selectedHslBand, sat = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Terang ${band.label}",
                        value = adjustments.hslLum[selectedHslBand],
                        onValueChange = { v -> onUpdate { it.withHsl(selectedHslBand, lum = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                }
                3 -> { // Detail & Efek
                    PrecisionSlider(
                        label = "Kejernihan",
                        value = adjustments.clarity,
                        onValueChange = { v -> onUpdate { it.copy(clarity = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Ketajaman",
                        value = adjustments.sharpen,
                        valueRange = 0f..1f,
                        valueFormatter = { "${(it * 100).toInt()}" },
                        onValueChange = { v -> onUpdate { it.copy(sharpen = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Vinyet",
                        value = adjustments.vignette,
                        onValueChange = { v -> onUpdate { it.copy(vignette = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Grain Film",
                        value = adjustments.grain,
                        valueRange = 0f..1f,
                        valueFormatter = { "${(it * 100).toInt()}" },
                        onValueChange = { v -> onUpdate { it.copy(grain = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Fade",
                        value = adjustments.fade,
                        valueRange = 0f..1f,
                        valueFormatter = { "${(it * 100).toInt()}" },
                        onValueChange = { v -> onUpdate { it.copy(fade = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                    PrecisionSlider(
                        label = "Halation",
                        value = adjustments.halation,
                        valueRange = 0f..1f,
                        valueFormatter = { "${(it * 100).toInt()}" },
                        onValueChange = { v -> onUpdate { it.copy(halation = v) } },
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Filter Panel (Live Photo Filmstrip Thumbnails + Intensity)
// ---------------------------------------------------------------------------

@Composable
fun FilterPanel(
    adjustments: ColorAdjustments,
    thumbnails: Map<FilterPreset, Bitmap>,
    onSelectPreset: (FilterPreset) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (adjustments.preset != FilterPreset.NONE) {
            PrecisionSlider(
                label = "Intensitas ${adjustments.preset.displayName}",
                value = adjustments.presetStrength,
                valueRange = 0f..1f,
                defaultValue = 0.85f,
                valueFormatter = { "${(it * 100).toInt()}%" },
                onValueChange = onStrengthChange,
                onDragStart = onDragStart,
                onDragEnd = onDragEnd
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterPreset.entries.forEach { preset ->
                val selected = adjustments.preset == preset
                val bmp = thumbnails[preset]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(64.dp)
                        .clickable { onSelectPreset(preset) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ArArColors.Surface2)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) ArArColors.AccentCyan else ArArColors.Border,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = preset.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.displayName,
                        fontSize = 11.sp,
                        color = if (selected) ArArColors.TextPrimary else ArArColors.TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Crop & Transform Panel
// ---------------------------------------------------------------------------

enum class CropAspectOption(val label: String, val ratio: Float?) {
    FREE("Bebas", null),
    IG_PORTRAIT("4:5 IG", 4f / 5f),
    SQUARE("1:1", 1f),
    STORY("9:16", 9f / 16f),
    LANDSCAPE("16:9", 16f / 9f),
    CLASSIC("3:2", 3f / 2f)
}

@Composable
fun CropPanel(
    selectedAspect: CropAspectOption,
    straightenDegrees: Float,
    onSelectAspect: (CropAspectOption) -> Unit,
    onStraightenChange: (Float) -> Unit,
    onRotate90: () -> Unit,
    onFlipH: () -> Unit,
    onApplyCrop: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CropAspectOption.entries.forEach { opt ->
                SelectablePill(
                    label = opt.label,
                    selected = selectedAspect == opt,
                    onClick = { onSelectAspect(opt) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        PrecisionSlider(
            label = "Luruskan",
            value = straightenDegrees,
            valueRange = -45f..45f,
            defaultValue = 0f,
            valueFormatter = { v -> "%.1f°".format(v) },
            onValueChange = onStraightenChange
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectablePill("Putar", selected = false, icon = Icons.AutoMirrored.Filled.RotateRight, onClick = onRotate90)
            SelectablePill("Balik", selected = false, icon = Icons.Default.Flip, onClick = onFlipH)
            Spacer(Modifier.weight(1f))
            PrimaryActionButton(text = "Terapkan", icon = Icons.Default.Check, onClick = onApplyCrop)
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Enhance & Anti-Noise Panel
// ---------------------------------------------------------------------------

@Composable
fun EnhancePanel(
    onRunEnhance: (mode: Int, strength: Float) -> Unit
) {
    var mode by remember { mutableIntStateOf(ImageEnhancerNative.MODE_DENOISE_ONLY) }
    var strength by remember { mutableStateOf(0.8f) }

    val modes = listOf(
        ImageEnhancerNative.MODE_DENOISE_ONLY to "Anti-Noise",
        ImageEnhancerNative.MODE_FACE_RESTORE to "Perjelas Wajah",
        ImageEnhancerNative.MODE_UNSHARP_MASK to "Tajamkan",
        ImageEnhancerNative.MODE_SUPER_RESOLUTION to "Resolusi 4X",
        ImageEnhancerNative.MODE_COMBINED_ENHANCE to "4X + Denoise"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modes.forEach { (id, title) ->
                SelectablePill(label = title, selected = mode == id, onClick = { mode = id })
            }
        }

        Spacer(Modifier.height(10.dp))

        PrecisionSlider(
            label = "Kekuatan",
            value = strength,
            valueRange = 0.2f..1.2f,
            defaultValue = 0.8f,
            valueFormatter = { "${(it * 100).toInt()}%" },
            onValueChange = { strength = it }
        )

        Spacer(Modifier.height(10.dp))

        PrimaryActionButton(
            text = "Terapkan",
            icon = Icons.Default.Check,
            onClick = { onRunEnhance(mode, strength) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// 5. Background Removal & Replacement Panel
// ---------------------------------------------------------------------------

@Composable
fun BackgroundPanel(
    initialConfig: BgReplaceConfig,
    onPickGalleryBg: () -> Unit,
    onApply: (BgReplaceConfig) -> Unit
) {
    var config by remember { mutableStateOf(initialConfig) }

    val bgSwatches = listOf(
        Color.White, Color(0xFF0F172A), Color(0xFFE2E8F0),
        Color(0xFFDB1514), Color(0xFF0054A6), Color(0xFF10B981),
        Color(0xFFF59E0B), Color(0xFFEC4899)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BgReplaceMode.entries.filter {
                it != BgReplaceMode.PASSPORT_RED && it != BgReplaceMode.PASSPORT_BLUE
            }.forEach { m ->
                SelectablePill(
                    label = m.displayName,
                    selected = config.mode == m,
                    onClick = {
                        config = config.copy(mode = m)
                        if (m == BgReplaceMode.CUSTOM_IMAGE) onPickGalleryBg()
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (config.mode == BgReplaceMode.SOLID_COLOR ||
            config.mode == BgReplaceMode.STUDIO_SHADOW ||
            config.mode == BgReplaceMode.STICKER_OUTLINE
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                bgSwatches.forEach { col ->
                    val argb = col.toArgb()
                    ColorSwatchDot(
                        color = col,
                        selected = config.solidColor == argb,
                        onClick = { config = config.copy(solidColor = argb) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        when (config.mode) {
            BgReplaceMode.BLUR_ORIGINAL -> {
                PrecisionSlider(
                    label = "Intensitas Blur",
                    value = config.blurRadius,
                    valueRange = 4f..32f,
                    defaultValue = 16f,
                    valueFormatter = { "${it.toInt()}" },
                    onValueChange = { config = config.copy(blurRadius = it) }
                )
            }
            BgReplaceMode.STICKER_OUTLINE -> {
                PrecisionSlider(
                    label = "Tebal Garis Tepi",
                    value = config.outlineWidth.toFloat(),
                    valueRange = 2f..28f,
                    defaultValue = 10f,
                    valueFormatter = { "${it.toInt()} px" },
                    onValueChange = { config = config.copy(outlineWidth = it.toInt()) }
                )
            }
            BgReplaceMode.STUDIO_SHADOW -> {
                PrecisionSlider(
                    label = "Kepekatan Bayangan",
                    value = config.shadowAlpha,
                    valueRange = 0.1f..0.95f,
                    defaultValue = 0.55f,
                    valueFormatter = { "${(it * 100).toInt()}%" },
                    onValueChange = { config = config.copy(shadowAlpha = it) }
                )
            }
            else -> {
                PrecisionSlider(
                    label = "Ketajaman Tepi",
                    value = config.edgeThreshold,
                    valueRange = 0.25f..0.75f,
                    defaultValue = 0.5f,
                    valueFormatter = { "${(it * 100).toInt()}%" },
                    onValueChange = { config = config.copy(edgeThreshold = it) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        PrimaryActionButton(
            text = "Terapkan",
            icon = Icons.Default.Check,
            onClick = { onApply(config) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// 6. Blur & Lighting 3D Panel
// ---------------------------------------------------------------------------

@Composable
fun BokehPanel(
    onApplyBokeh: (blurRadius: Float, focusDepth: Float) -> Unit,
    onApplyRelight: (x: Float, y: Float, z: Float, color: Int, intensity: Float) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0 = Blur Latar, 1 = Arah Cahaya
    var blurRadius by remember { mutableStateOf(16f) }
    var focusDepth by remember { mutableStateOf(0.85f) }

    var lightX by remember { mutableStateOf(0.25f) }
    var lightY by remember { mutableStateOf(0.25f) }
    var intensity by remember { mutableStateOf(0.75f) }
    var lightColor by remember { mutableStateOf(Color(0xFFFFD599)) }

    val lightSwatches = listOf(
        Color.White, Color(0xFFFFD599), Color(0xFF93C5FD),
        Color(0xFF22D3EE), Color(0xFFF472B6), Color(0xFFA78BFA)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectablePill("Blur Latar", selected = mode == 0, onClick = { mode = 0 })
            SelectablePill("Arah Cahaya", selected = mode == 1, onClick = { mode = 1 })
        }

        Spacer(Modifier.height(10.dp))

        if (mode == 0) {
            PrecisionSlider(
                label = "Intensitas Blur",
                value = blurRadius,
                valueRange = 4f..32f,
                defaultValue = 16f,
                valueFormatter = { "${it.toInt()}" },
                onValueChange = { blurRadius = it }
            )
            PrecisionSlider(
                label = "Jarak Fokus",
                value = focusDepth,
                valueRange = 0.0f..1.0f,
                defaultValue = 0.85f,
                valueFormatter = { "${(it * 100).toInt()}%" },
                onValueChange = { focusDepth = it }
            )
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = "Terapkan Blur",
                icon = Icons.Default.Check,
                onClick = { onApplyBokeh(blurRadius, focusDepth) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                lightSwatches.forEach { col ->
                    ColorSwatchDot(color = col, selected = lightColor == col, onClick = { lightColor = col })
                }
            }
            Spacer(Modifier.height(6.dp))
            PrecisionSlider(
                label = "Posisi Horizontal",
                value = lightX,
                valueRange = 0f..1f,
                defaultValue = 0.25f,
                valueFormatter = { "${(it * 100).toInt()}%" },
                onValueChange = { lightX = it }
            )
            PrecisionSlider(
                label = "Intensitas Cahaya",
                value = intensity,
                valueRange = 0.1f..1.4f,
                defaultValue = 0.75f,
                valueFormatter = { "${(it * 100).toInt()}%" },
                onValueChange = { intensity = it }
            )
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = "Terapkan Cahaya",
                icon = Icons.Default.Check,
                onClick = { onApplyRelight(lightX, lightY, 0.75f, lightColor.toArgb(), intensity) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 7. Object Erase Panel
// ---------------------------------------------------------------------------

@Composable
fun ErasePanel(
    brushSize: Float,
    isErasing: Boolean,
    onBrushSizeChange: (Float) -> Unit,
    onToggleErasing: (Boolean) -> Unit,
    onClearMask: () -> Unit,
    onApplyErase: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PrecisionSlider(
            label = "Ukuran Kuas",
            value = brushSize,
            valueRange = 10f..70f,
            defaultValue = 28f,
            valueFormatter = { "${it.toInt()} px" },
            onValueChange = onBrushSizeChange
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectablePill("Tandai", selected = !isErasing, icon = Icons.Default.Brush, onClick = { onToggleErasing(false) })
            SelectablePill("Hapus Kuas", selected = isErasing, icon = Icons.Default.CleaningServices, onClick = { onToggleErasing(true) })
            Text(
                text = "Bersihkan",
                style = MaterialTheme.typography.labelLarge,
                color = ArArColors.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .clickable(onClick = onClearMask)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        PrimaryActionButton(
            text = "Hapus Area Ditandai",
            icon = Icons.Default.Check,
            onClick = onApplyErase,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// 8. Non-Anime Animation Panel (7 On-Demand TFLite Models)
// ---------------------------------------------------------------------------

@Composable
fun AnimationPanel(
    selectedStyle: AnimationStyle,
    modelsState: AnimationModelsUiState,
    onSelectStyle: (AnimationStyle) -> Unit,
    onDownloadStyle: (AnimationStyle) -> Unit,
    onCancelDownload: () -> Unit,
    onOpenModelManager: () -> Unit,
    onApply: (style: AnimationStyle, strength: Float) -> Unit
) {
    var strength by remember { mutableStateOf(0.92f) }

    val isInstalled = selectedStyle.id in modelsState.installedIds
    val isDownloadingSelected = modelsState.downloadingId == selectedStyle.id
    val isAnyDownloading = modelsState.downloadingId != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimationStyle.entries.forEach { style ->
                val selected = selectedStyle == style
                val installed = style.id in modelsState.installedIds
                val downloading = modelsState.downloadingId == style.id

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(if (selected) ArArColors.Surface3 else ArArColors.Surface2)
                        .border(
                            width = 1.dp,
                            color = if (selected) ArArColors.AccentCyan else ArArColors.Border,
                            shape = RoundedCornerShape(100)
                        )
                        .clickable { onSelectStyle(style) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            installed -> Icons.Default.CheckCircle
                            else -> Icons.Default.CloudDownload
                        },
                        contentDescription = null,
                        tint = when {
                            installed -> ArArColors.Success
                            downloading -> ArArColors.AccentCyan
                            else -> ArArColors.TextMuted
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = style.displayName,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) ArArColors.TextPrimary else ArArColors.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            isDownloadingSelected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Mengunduh ${selectedStyle.displayName}... ${(modelsState.downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArArColors.AccentCyan
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .clickable(onClick = onCancelDownload)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Batal", tint = ArArColors.TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Batal", fontSize = 12.sp, color = ArArColors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { modelsState.downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(100)),
                    color = ArArColors.AccentCyan,
                    trackColor = ArArColors.Surface3
                )
            }

            isInstalled -> {
                PrecisionSlider(
                    label = "Intensitas",
                    value = strength,
                    valueRange = 0.25f..1.0f,
                    defaultValue = 0.92f,
                    valueFormatter = { "${(it * 100).toInt()}%" },
                    onValueChange = { strength = it }
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PrimaryActionButton(
                        text = "Terapkan ${selectedStyle.displayName}",
                        icon = Icons.Default.Check,
                        onClick = { onApply(selectedStyle, strength) },
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(100))
                            .background(ArArColors.Surface2)
                            .border(1.dp, ArArColors.Border, RoundedCornerShape(100))
                            .clickable(onClick = onOpenModelManager)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Kelola Model",
                            tint = ArArColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedStyle.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = ArArColors.TextSecondary
                        )
                        Text(
                            text = "Ukuran unduhan: ${selectedStyle.sizeLabel} · Sekali unduh, jalan offline",
                            fontSize = 11.sp,
                            color = ArArColors.TextMuted
                        )
                    }
                    Text(
                        text = "Kelola",
                        style = MaterialTheme.typography.labelLarge,
                        color = ArArColors.AccentCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .clickable(onClick = onOpenModelManager)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(
                    text = "Unduh ${selectedStyle.displayName} (${selectedStyle.sizeLabel})",
                    icon = Icons.Default.CloudDownload,
                    enabled = !isAnyDownloading,
                    onClick = { onDownloadStyle(selectedStyle) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 9. Text Overlay Panel
// ---------------------------------------------------------------------------

@Composable
fun TextPanel(
    config: TextOverlayConfig,
    onConfigChange: (TextOverlayConfig) -> Unit,
    onApply: () -> Unit
) {
    val swatches = listOf(Color.White, Color.Black, Color(0xFFFDE047), Color(0xFF22D3EE), Color(0xFFF472B6), Color(0xFFFB923C))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = config.text,
            onValueChange = { onConfigChange(config.copy(text = it)) },
            placeholder = { Text("Tulis teks...", color = ArArColors.TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ArArColors.AccentCyan,
                unfocusedBorderColor = ArArColors.Border,
                focusedTextColor = ArArColors.TextPrimary,
                unfocusedTextColor = ArArColors.TextPrimary
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextFontFamily.entries.forEach { font ->
                SelectablePill(font.displayName, selected = config.font == font, onClick = {
                    onConfigChange(config.copy(font = font))
                })
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextStyleModel.entries.forEach { st ->
                SelectablePill(st.displayName, selected = config.styleModel == st, onClick = {
                    onConfigChange(config.copy(styleModel = st))
                })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            swatches.forEach { c ->
                ColorSwatchDot(c, selected = config.textColor == c.toArgb(), onClick = {
                    onConfigChange(config.copy(textColor = c.toArgb()))
                })
            }
        }

        PrecisionSlider(
            label = "Ukuran",
            value = config.fontSizeSp,
            valueRange = 16f..72f,
            defaultValue = 32f,
            valueFormatter = { "${it.toInt()}" },
            onValueChange = { onConfigChange(config.copy(fontSizeSp = it)) }
        )

        PrecisionSlider(
            label = "Posisi Vertikal",
            value = config.positionYFraction,
            valueRange = 0.1f..0.95f,
            defaultValue = 0.82f,
            valueFormatter = { "${(it * 100).toInt()}%" },
            onValueChange = { onConfigChange(config.copy(positionYFraction = it)) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Taruh di belakang subjek", style = MaterialTheme.typography.bodyMedium, color = ArArColors.TextPrimary)
            Switch(
                checked = config.behindSubject,
                onCheckedChange = { onConfigChange(config.copy(behindSubject = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = ArArColors.Accent)
            )
        }

        PrimaryActionButton(
            text = "Tambahkan Teks",
            icon = Icons.Default.Check,
            enabled = config.text.isNotBlank(),
            onClick = onApply,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// 10. Passport Photo Panel
// ---------------------------------------------------------------------------

@Composable
fun PassportPanel(
    onGenerate: (PassportSize, PassportBgColor, Boolean) -> Unit
) {
    var size by remember { mutableStateOf(PassportSize.SIZE_3X4) }
    var bg by remember { mutableStateOf(PassportBgColor.INDONESIA_RED) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PassportSize.entries.forEach { s ->
                SelectablePill(s.displayName, selected = size == s, onClick = { size = s })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PassportBgColor.entries.forEach { c ->
                val color = Color(android.graphics.Color.parseColor(c.colorHex))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(if (bg == c) ArArColors.Surface3 else ArArColors.Surface2)
                        .border(1.dp, if (bg == c) ArArColors.AccentCyan else ArArColors.Border, RoundedCornerShape(100))
                        .clickable { bg = c }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(7.dp))
                    Text(c.displayName, fontSize = 12.sp, color = ArArColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryActionButton(
                text = "Buat Pasfoto",
                onClick = { onGenerate(size, bg, false) },
                modifier = Modifier.weight(1f)
            )
            SelectablePill(
                label = "Lembar 4R",
                selected = false,
                onClick = { onGenerate(size, bg, true) },
                modifier = Modifier.height(44.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 11. Export Bottom Sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    hasTransparency: Boolean,
    onDismiss: () -> Unit,
    onSave: (ExportSizeOption, Boolean, Int) -> Unit,
    onShare: (ExportSizeOption, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSize by remember { mutableStateOf(ExportSizeOption.SOCIAL_HD) }
    var forcePng by remember { mutableStateOf(hasTransparency) }
    var jpegQuality by remember { mutableStateOf(95f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArArColors.Surface1,
        contentColor = ArArColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Simpan Foto", style = MaterialTheme.typography.titleLarge, color = ArArColors.TextPrimary)

            ExportSizeOption.entries.forEach { opt ->
                val selected = selectedSize == opt
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) ArArColors.Surface3 else ArArColors.Surface2)
                        .border(1.dp, if (selected) ArArColors.AccentCyan else ArArColors.Border, RoundedCornerShape(12.dp))
                        .clickable { selectedSize = opt }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(opt.label, style = MaterialTheme.typography.titleSmall, color = ArArColors.TextPrimary)
                        Text(opt.subtitle, style = MaterialTheme.typography.bodySmall, color = ArArColors.TextSecondary)
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = ArArColors.AccentCyan)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Simpan sebagai PNG", style = MaterialTheme.typography.bodyMedium, color = ArArColors.TextPrimary)
                Switch(
                    checked = forcePng,
                    onCheckedChange = { forcePng = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = ArArColors.Accent)
                )
            }

            if (!forcePng) {
                PrecisionSlider(
                    label = "Kualitas JPEG",
                    value = jpegQuality,
                    valueRange = 75f..100f,
                    defaultValue = 95f,
                    valueFormatter = { "${it.toInt()}%" },
                    onValueChange = { jpegQuality = it }
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryActionButton(
                    text = "Simpan ke Galeri",
                    icon = Icons.Default.Download,
                    onClick = { onSave(selectedSize, forcePng, jpegQuality.toInt()) },
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(100))
                        .background(ArArColors.Surface2)
                        .border(1.dp, ArArColors.Border, RoundedCornerShape(100))
                        .clickable { onShare(selectedSize, forcePng) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = "Bagikan", tint = ArArColors.TextPrimary, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Bagikan", color = ArArColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
