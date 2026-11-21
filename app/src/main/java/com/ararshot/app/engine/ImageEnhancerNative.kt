package com.ararshot.app.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.ararshot.app.model.ColorAdjustments
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

data class DetectedFaceBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val score: Float,
    val landmarks: FloatArray
)

object ImageEnhancerNative {
    private const val TAG = "ArArNative"

    const val MODE_SUPER_RESOLUTION = 0
    const val MODE_BILATERAL_DENOISE = 1
    const val MODE_UNSHARP_MASK = 2
    const val MODE_COMBINED_ENHANCE = 3
    const val MODE_FACE_RESTORE = 4
    const val MODE_DENOISE_ONLY = 5

    const val ANIME_STYLE_SMOOTH = 0
    const val ANIME_STYLE_ILLUSTRATION = 1
    const val ANIME_STYLE_SKETCH = 2

    const val MAX_OUTPUT_DIMENSION = 4096

    @Volatile
    private var isNativeLibraryLoaded = false

    @Volatile
    private var isAssetManagerReady = false

    private var loadAttempted = false

    @Synchronized
    fun ensureNativeLoaded(): Boolean {
        if (loadAttempted) return isNativeLibraryLoaded
        loadAttempted = true

        try {
            System.loadLibrary("ncnn")
        } catch (t: Throwable) {
            Log.w(TAG, "libncnn load notice: ${t.message}")
        }

        try {
            System.loadLibrary("arar_engine")
            isNativeLibraryLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load arar_engine: ${t.message}")
            isNativeLibraryLoaded = false
        }
        return isNativeLibraryLoaded
    }

    fun isNativeLoaded(): Boolean = isNativeLibraryLoaded
    fun isReady(): Boolean = isNativeLibraryLoaded && isAssetManagerReady

    private external fun initAssetManagerNative(assetManager: AssetManager): Boolean
    private external fun releaseModelsNative()

    private external fun applyLinearColorNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        params: FloatArray
    ): IntArray?

    private external fun analyzeAutoEnhanceNative(
        srcPixels: IntArray,
        width: Int,
        height: Int
    ): FloatArray?

    external fun processTileNative(
        inPixels: IntArray,
        width: Int,
        height: Int,
        mode: Int,
        strength: Float
    ): IntArray?

    external fun processBitmapNative(
        bitmapIn: Bitmap,
        bitmapOut: Bitmap,
        mode: Int,
        strength: Float
    ): Boolean

    external fun blendTileWithWeightNative(
        tilePixels: IntArray,
        targetPixels: IntArray,
        weightMap: FloatArray,
        tileW: Int,
        tileH: Int,
        targetW: Int,
        targetH: Int,
        offsetX: Int,
        offsetY: Int,
        overlapPx: Int
    )

    private external fun detectFacesNative(
        srcPixels: IntArray,
        width: Int,
        height: Int
    ): FloatArray?

    private external fun restoreFacesNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        strength: Float
    ): IntArray?

    private external fun extractAlphaMatteNative(
        srcPixels: IntArray,
        coarseMask: FloatArray?,
        width: Int,
        height: Int,
        isPortraitHint: Boolean
    ): FloatArray?

    private external fun estimateDepthNative(
        srcPixels: IntArray,
        subjectMask: FloatArray?,
        width: Int,
        height: Int
    ): FloatArray?

    private external fun inpaintNative(
        srcPixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray?

    private external fun applyBokehNative(
        srcPixels: IntArray,
        depthMask: FloatArray,
        width: Int,
        height: Int,
        blurRadius: Float,
        focusDepth: Float
    ): IntArray?

    private external fun applyRelightingNative(
        srcPixels: IntArray,
        depthMask: FloatArray,
        width: Int,
        height: Int,
        lightX: Float,
        lightY: Float,
        lightZ: Float,
        lightR: Float,
        lightG: Float,
        lightB: Float,
        intensity: Float
    ): IntArray?

    private external fun applyAnimeStylizeNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        styleMode: Int,
        strength: Float
    ): IntArray?

    private external fun applyStudioShadowOutlineNative(
        srcPixels: IntArray,
        mask: FloatArray,
        width: Int,
        height: Int,
        outlineWidth: Int,
        outlineColor: Int,
        shadowOffsetX: Int,
        shadowOffsetY: Int,
        shadowBlur: Float,
        shadowAlpha: Float,
        bgColor: Int
    ): IntArray?

    /**
     * Registers AssetManager for instant startup; NCNN models are lazy-loaded on first use.
     */
    @Synchronized
    fun initEngine(context: Context): Boolean {
        if (isAssetManagerReady) return true
        if (!ensureNativeLoaded()) return false
        return try {
            isAssetManagerReady = initAssetManagerNative(context.applicationContext.assets)
            isAssetManagerReady
        } catch (t: Throwable) {
            Log.e(TAG, "initEngine error: ${t.message}", t)
            false
        }
    }

    fun releaseMemory() {
        if (isNativeLibraryLoaded) {
            try {
                releaseModelsNative()
            } catch (t: Throwable) {
                Log.w(TAG, "releaseMemory error: ${t.message}")
            }
        }
    }

    fun applyLinearColor(input: Bitmap, adjustments: ColorAdjustments): Bitmap {
        if (adjustments.isIdentity()) return input
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val srcPixels = IntArray(width * height)
        input.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val params = adjustments.toFloatArray()

        if (isNativeLibraryLoaded) {
            try {
                val outPixels = applyLinearColorNative(srcPixels, width, height, params)
                if (outPixels != null && outPixels.size == width * height) {
                    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    out.setHasAlpha(input.hasAlpha())
                    out.setPixels(outPixels, 0, width, 0, 0, width, height)
                    return out
                }
            } catch (t: Throwable) {
                Log.e(TAG, "applyLinearColor native error: ${t.message}", t)
            }
        }
        return applyLinearColorJvmFallback(input, srcPixels, adjustments)
    }

    fun analyzeAutoEnhance(input: Bitmap, current: ColorAdjustments): ColorAdjustments {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val srcPixels = IntArray(width * height)
        input.getPixels(srcPixels, 0, width, 0, 0, width, height)

        if (isNativeLibraryLoaded) {
            try {
                val raw = analyzeAutoEnhanceNative(srcPixels, width, height)
                if (raw != null && raw.size >= ColorAdjustments.PARAMS_COUNT) {
                    return ColorAdjustments.fromAutoParams(raw, current)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "analyzeAutoEnhance error: ${t.message}", t)
            }
        }

        // Clean JVM fallback for unit tests
        var sumLum = 0.0
        val step = max(1, minOf(width, height) / 128)
        var count = 0
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val p = srcPixels[y * width + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                sumLum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                count++
            }
        }
        val avg = if (count > 0) (sumLum / count).toFloat() else 0.5f
        val expEv = ((0.48f - avg) * 1.2f).coerceIn(-0.6f, 0.7f)
        return current.copy(exposure = expEv, contrast = 0.14f, shadows = 0.15f, vibrance = 0.15f)
    }

    fun capForSuperResolution(input: Bitmap, scale: Int = 4): Bitmap {
        val maxInputSide = MAX_OUTPUT_DIMENSION / scale
        val curMax = max(input.width, input.height)
        if (curMax <= maxInputSide) return input
        val ratio = maxInputSide.toFloat() / curMax.toFloat()
        val newW = max(1, (input.width * ratio).toInt())
        val newH = max(1, (input.height * ratio).toInt())
        return Bitmap.createScaledBitmap(input, newW, newH, true)
    }

    fun enhanceImage(input: Bitmap, mode: Int = MODE_SUPER_RESOLUTION, strength: Float = 0.85f): Bitmap {
        ensureNativeLoaded()
        val isSuperRes = (mode == MODE_SUPER_RESOLUTION || mode == MODE_COMBINED_ENHANCE)
        val safeInput = if (isSuperRes) capForSuperResolution(input, 4) else input

        val width = safeInput.width
        val height = safeInput.height
        val inPixels = IntArray(width * height)
        safeInput.getPixels(inPixels, 0, width, 0, 0, width, height)

        return try {
            val outW = if (isSuperRes) width * 4 else width
            val outH = if (isSuperRes) height * 4 else height

            val processedPixels = if (isNativeLibraryLoaded) {
                when (mode) {
                    MODE_FACE_RESTORE -> restoreFacesNative(inPixels, width, height, strength)
                    else -> processTileNative(inPixels, width, height, mode, strength)
                }
            } else {
                if (isSuperRes) {
                    val scaled = Bitmap.createScaledBitmap(safeInput, outW, outH, true)
                    val outArr = IntArray(outW * outH)
                    scaled.getPixels(outArr, 0, outW, 0, 0, outW, outH)
                    outArr
                } else {
                    inPixels
                }
            }

            if (processedPixels != null && processedPixels.size == outW * outH) {
                val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(processedPixels, 0, outW, 0, 0, outW, outH)
                outBitmap
            } else {
                safeInput
            }
        } catch (t: Throwable) {
            Log.e(TAG, "enhanceImage error: ${t.message}", t)
            safeInput
        }
    }

    fun detectFaces(input: Bitmap): List<DetectedFaceBox> {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        if (!isNativeLibraryLoaded) return emptyList()
        return try {
            val raw = detectFacesNative(inPixels, width, height) ?: return emptyList()
            val count = raw.size / 15
            List(count) { i ->
                val base = i * 15
                val kps = FloatArray(10)
                System.arraycopy(raw, base + 5, kps, 0, 10)
                DetectedFaceBox(
                    x = raw[base],
                    y = raw[base + 1],
                    width = raw[base + 2],
                    height = raw[base + 3],
                    score = raw[base + 4],
                    landmarks = kps
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "detectFaces error: ${t.message}", t)
            emptyList()
        }
    }

    fun restoreFaces(input: Bitmap, strength: Float = 0.8f): Bitmap {
        return enhanceImage(input, MODE_FACE_RESTORE, strength)
    }

    fun extractAlphaMatte(
        input: Bitmap,
        coarseMask: FloatArray? = null,
        isPortraitHint: Boolean = true
    ): FloatArray {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val total = width * height
        val inPixels = IntArray(total)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        if (isNativeLibraryLoaded) {
            try {
                val refined = extractAlphaMatteNative(inPixels, coarseMask, width, height, isPortraitHint)
                if (refined != null && refined.size == total) return refined
            } catch (t: Throwable) {
                Log.e(TAG, "extractAlphaMatte error: ${t.message}", t)
            }
        }
        return coarseMask ?: FloatArray(total) { 1.0f }
    }

    fun estimateDepth(input: Bitmap, subjectMask: FloatArray? = null): FloatArray {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val total = width * height
        val inPixels = IntArray(total)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        if (isNativeLibraryLoaded) {
            try {
                val depth = estimateDepthNative(inPixels, subjectMask, width, height)
                if (depth != null && depth.size == total) return depth
            } catch (t: Throwable) {
                Log.e(TAG, "estimateDepth error: ${t.message}", t)
            }
        }
        return subjectMask ?: FloatArray(total) { i -> (i / width).toFloat() / height.coerceAtLeast(1) }
    }

    fun inpaint(input: Bitmap, mask: Bitmap, radius: Int = 6): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val scaledMask = if (mask.width != width || mask.height != height) {
            Bitmap.createScaledBitmap(mask, width, height, false)
        } else mask

        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)
        val maskPixels = IntArray(width * height)
        scaledMask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        return try {
            val resultPixels = if (isNativeLibraryLoaded) {
                inpaintNative(inPixels, maskPixels, width, height, radius)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(resultPixels, 0, width, 0, 0, width, height)
                }
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inpaint error: ${t.message}", t)
            input
        }
    }

    fun depthBokeh(input: Bitmap, depthMask: FloatArray, blurRadius: Float = 16f, focusDepth: Float = 1.0f): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        return try {
            val resultPixels = if (isNativeLibraryLoaded && depthMask.size == width * height) {
                applyBokehNative(inPixels, depthMask, width, height, blurRadius, focusDepth)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(resultPixels, 0, width, 0, 0, width, height)
                }
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "depthBokeh error: ${t.message}", t)
            input
        }
    }

    fun relighting3D(
        input: Bitmap,
        depthMask: FloatArray,
        lightX: Float,
        lightY: Float,
        lightZ: Float,
        lightColor: Int,
        intensity: Float
    ): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        val r = ((lightColor ushr 16) and 0xFF) / 255.0f
        val g = ((lightColor ushr 8) and 0xFF) / 255.0f
        val b = (lightColor and 0xFF) / 255.0f

        return try {
            val resultPixels = if (isNativeLibraryLoaded && depthMask.size == width * height) {
                applyRelightingNative(inPixels, depthMask, width, height, lightX, lightY, lightZ, r, g, b, intensity)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(resultPixels, 0, width, 0, 0, width, height)
                }
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "relighting3D error: ${t.message}", t)
            input
        }
    }

    fun animeStylize(input: Bitmap, styleMode: Int = ANIME_STYLE_SMOOTH, strength: Float = 0.9f): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        return try {
            val resultPixels = if (isNativeLibraryLoaded) {
                applyAnimeStylizeNative(inPixels, width, height, styleMode, strength)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(resultPixels, 0, width, 0, 0, width, height)
                }
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "animeStylize error: ${t.message}", t)
            input
        }
    }

    fun studioShadowOutline(
        input: Bitmap,
        mask: FloatArray,
        shadowDx: Int = 10,
        shadowDy: Int = 18,
        shadowRadius: Int = 16,
        shadowAlpha: Float = 0.55f,
        outlineWidth: Int = 10,
        outlineColor: Int = android.graphics.Color.WHITE,
        bgColor: Int = android.graphics.Color.WHITE
    ): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        return try {
            val resultPixels = if (isNativeLibraryLoaded && mask.size == width * height) {
                applyStudioShadowOutlineNative(
                    inPixels, mask, width, height,
                    outlineWidth, outlineColor, shadowDx, shadowDy,
                    shadowRadius.toFloat(), shadowAlpha, bgColor
                )
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(resultPixels, 0, width, 0, 0, width, height)
                }
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "studioShadowOutline error: ${t.message}", t)
            input
        }
    }

    /**
     * Exact linear-light JVM fallback so JVM unit tests verify physical exposure/WB math accurately.
     */
    private fun applyLinearColorJvmFallback(
        src: Bitmap,
        srcPixels: IntArray,
        adj: ColorAdjustments
    ): Bitmap {
        val outPixels = IntArray(srcPixels.size)
        val expGain = 2.0f.pow(adj.exposure)
        var wbR = 2.0f.pow(adj.temp * 0.35f + adj.tint * 0.14f)
        var wbG = 2.0f.pow(-adj.tint * 0.22f)
        var wbB = 2.0f.pow(-adj.temp * 0.38f + adj.tint * 0.14f)
        val norm = 1.0f / (0.2126f * wbR + 0.7152f * wbG + 0.0722f * wbB)
        wbR *= norm; wbG *= norm; wbB *= norm

        fun sToL(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        fun roll(x: Float): Float = if (x <= 0.85f) x.coerceAtLeast(0f) else 0.85f + 0.15f * (1f - exp(-(x - 0.85f) / 0.15f))
        fun lToS(l: Float): Float {
            val c = l.coerceIn(0f, 1f)
            return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
        }

        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val a = (p ushr 24) and 0xFF
            val rL = sToL(((p ushr 16) and 0xFF) / 255f) * expGain * wbR
            val gL = sToL(((p ushr 8) and 0xFF) / 255f) * expGain * wbG
            val bL = sToL((p and 0xFF) / 255f) * expGain * wbB

            var r = lToS(roll(rL))
            var g = lToS(roll(gL))
            var b = lToS(roll(bL))

            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val satF = (1f + adj.saturation).coerceAtLeast(0f)
            r = (lum + (r - lum) * satF).coerceIn(0f, 1f)
            g = (lum + (g - lum) * satF).coerceIn(0f, 1f)
            b = (lum + (b - lum) * satF).coerceIn(0f, 1f)

            outPixels[i] = (a shl 24) or
                ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(src.hasAlpha())
            setPixels(outPixels, 0, src.width, 0, 0, src.width, src.height)
        }
    }
}
