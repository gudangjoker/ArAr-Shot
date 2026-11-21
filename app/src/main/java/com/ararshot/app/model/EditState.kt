package com.ararshot.app.model

import android.net.Uri
import kotlin.math.abs

enum class EditorTool(val label: String) {
    ADJUST("Sesuaikan"),
    FILTER("Filter"),
    CROP("Potong"),
    ENHANCE("Tingkatkan"),
    BACKGROUND("Latar"),
    BOKEH("Blur"),
    ERASE("Hapus"),
    ANIMATION("Animasi"),
    TEXT("Teks"),
    PASSPORT("Pasfoto")
}

enum class FilterPreset(val id: Int, val displayName: String) {
    NONE(0, "Asli"),
    NATURAL(1, "Alami"),
    FILM_WARM(2, "Film"),
    COOL(3, "Sejuk"),
    GOLDEN(4, "Senja"),
    SOFT_PORTRAIT(5, "Lembut"),
    MOODY(6, "Moody"),
    VIVID(7, "Cerah"),
    MATTE(8, "Matte"),
    BW_CLASSIC(9, "B&W"),
    BW_DRAMATIC(10, "Noir"),
    RETRO_CCD(11, "Retro"),
    CINEMA(12, "Sinema")
}

enum class HslBand(val index: Int, val label: String, val colorHex: Long) {
    RED(0, "Merah", 0xFFEF4444),
    ORANGE(1, "Oranye", 0xFFF97316),
    YELLOW(2, "Kuning", 0xFFEAB308),
    GREEN(3, "Hijau", 0xFF22C55E),
    AQUA(4, "Aqua", 0xFF06B6D4),
    BLUE(5, "Biru", 0xFF3B82F6),
    PURPLE(6, "Ungu", 0xFFA855F7),
    MAGENTA(7, "Magenta", 0xFFEC4899)
}

enum class ExportSizeOption(val label: String, val subtitle: String, val maxEdgePx: Int) {
    ORIGINAL("Ukuran Asli", "Resolusi penuh", 4096),
    SOCIAL_HD("Optimal Sosmed", "Maks. 1440 px, tajam untuk IG", 1440),
    COMPACT("Ringan", "Maks. 1080 px, hemat ruang", 1080)
}

data class GalleryPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedSec: Long
)

/**
 * Non-destructive Linear-Light color, lighting, HSL & filter state.
 * Packed into 42 floats matching native_enhancer.h COLOR_PARAMS_COUNT.
 */
data class ColorAdjustments(
    val exposure: Float = 0f,      // -2.0 .. +2.0 EV
    val contrast: Float = 0f,      // -1.0 .. +1.0
    val highlights: Float = 0f,    // -1.0 .. +1.0
    val shadows: Float = 0f,       // -1.0 .. +1.0
    val whites: Float = 0f,        // -1.0 .. +1.0
    val blacks: Float = 0f,        // -1.0 .. +1.0
    val temp: Float = 0f,          // -1.0 .. +1.0
    val tint: Float = 0f,          // -1.0 .. +1.0
    val vibrance: Float = 0f,      // -1.0 .. +1.0
    val saturation: Float = 0f,    // -1.0 .. +1.0
    val clarity: Float = 0f,       // -1.0 .. +1.0
    val sharpen: Float = 0f,       //  0.0 .. +1.0
    val fade: Float = 0f,          //  0.0 .. +1.0
    val vignette: Float = 0f,      // -1.0 .. +1.0
    val grain: Float = 0f,         //  0.0 .. +1.0
    val halation: Float = 0f,      //  0.0 .. +1.0
    val preset: FilterPreset = FilterPreset.NONE,
    val presetStrength: Float = 0.85f,
    val hslHue: List<Float> = List(8) { 0f },
    val hslSat: List<Float> = List(8) { 0f },
    val hslLum: List<Float> = List(8) { 0f }
) {
    fun isIdentity(): Boolean {
        val eps = 0.005f
        if (abs(exposure) > eps || abs(contrast) > eps || abs(highlights) > eps ||
            abs(shadows) > eps || abs(whites) > eps || abs(blacks) > eps ||
            abs(temp) > eps || abs(tint) > eps || abs(vibrance) > eps ||
            abs(saturation) > eps || abs(clarity) > eps || sharpen > eps ||
            fade > eps || abs(vignette) > eps || grain > eps || halation > eps
        ) return false
        if (preset != FilterPreset.NONE && presetStrength > eps) return false
        if (hslHue.any { abs(it) > eps } || hslSat.any { abs(it) > eps } || hslLum.any { abs(it) > eps }) {
            return false
        }
        return true
    }

    fun toFloatArray(): FloatArray {
        val arr = FloatArray(PARAMS_COUNT)
        arr[0] = exposure
        arr[1] = contrast
        arr[2] = highlights
        arr[3] = shadows
        arr[4] = whites
        arr[5] = blacks
        arr[6] = temp
        arr[7] = tint
        arr[8] = vibrance
        arr[9] = saturation
        arr[10] = clarity
        arr[11] = sharpen
        arr[12] = fade
        arr[13] = vignette
        arr[14] = grain
        arr[15] = halation
        arr[16] = preset.id.toFloat()
        arr[17] = if (preset == FilterPreset.NONE) 0f else presetStrength
        for (i in 0 until 8) {
            arr[18 + i] = hslHue.getOrElse(i) { 0f }
            arr[26 + i] = hslSat.getOrElse(i) { 0f }
            arr[34 + i] = hslLum.getOrElse(i) { 0f }
        }
        return arr
    }

    fun withHsl(bandIndex: Int, hue: Float? = null, sat: Float? = null, lum: Float? = null): ColorAdjustments {
        val newH = hslHue.toMutableList().apply { if (hue != null) this[bandIndex] = hue.coerceIn(-1f, 1f) }
        val newS = hslSat.toMutableList().apply { if (sat != null) this[bandIndex] = sat.coerceIn(-1f, 1f) }
        val newL = hslLum.toMutableList().apply { if (lum != null) this[bandIndex] = lum.coerceIn(-1f, 1f) }
        return copy(hslHue = newH, hslSat = newS, hslLum = newL)
    }

    companion object {
        const val PARAMS_COUNT = 42

        fun fromAutoParams(params: FloatArray, current: ColorAdjustments): ColorAdjustments {
            if (params.size < 12) return current
            return current.copy(
                exposure = params[0].coerceIn(-2f, 2f),
                contrast = params[1].coerceIn(-1f, 1f),
                highlights = params[2].coerceIn(-1f, 1f),
                shadows = params[3].coerceIn(-1f, 1f),
                whites = params[4].coerceIn(-1f, 1f),
                blacks = params[5].coerceIn(-1f, 1f),
                temp = params[6].coerceIn(-1f, 1f),
                tint = params[7].coerceIn(-1f, 1f),
                vibrance = params[8].coerceIn(-1f, 1f),
                clarity = params[10].coerceIn(-1f, 1f)
            )
        }
    }
}
