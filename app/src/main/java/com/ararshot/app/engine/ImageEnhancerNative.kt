package com.ararshot.app.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.max
import kotlin.math.min

data class DetectedFaceBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val score: Float,
    val landmarks: FloatArray
)

object ImageEnhancerNative {
    private const val TAG = "ImageEnhancerNative"

    const val MODE_SUPER_RESOLUTION = 0
    const val MODE_BILATERAL_DENOISE = 1
    const val MODE_UNSHARP_MASK = 2
    const val MODE_COMBINED_ENHANCE = 3
    const val MODE_FACE_RESTORE = 4

    const val MAX_OUTPUT_DIMENSION = 4096

    @Volatile
    private var isNativeLibraryLoaded = false
    @Volatile
    private var isModelLoaded = false
    private var loadAttempted = false

    /**
     * Lazily and safely loads the native C++ libraries (libncnn and libarar_engine).
     * Avoids startup class-initialization crashes on any device or JVM test environment.
     */
    @Synchronized
    fun ensureNativeLoaded(): Boolean {
        if (loadAttempted) return isNativeLibraryLoaded
        loadAttempted = true

        try {
            System.loadLibrary("ncnn")
            Log.i(TAG, "libncnn loaded successfully.")
        } catch (t: Throwable) {
            Log.w(TAG, "libncnn load status: ${t.message}")
        }

        try {
            System.loadLibrary("arar_engine")
            isNativeLibraryLoaded = true
            Log.i(TAG, "Native library 'arar_engine' loaded successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load 'arar_engine' native library: ${t.message}")
            isNativeLibraryLoaded = false
        }

        return isNativeLibraryLoaded
    }

    fun isNativeLoaded(): Boolean = isNativeLibraryLoaded
    fun isModelReady(): Boolean = isModelLoaded

    private external fun loadModelNative(
        assetManager: AssetManager,
        paramPath: String,
        binPath: String
    ): Boolean

    private external fun initAllModelsNative(
        assetManager: AssetManager
    ): Boolean

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

    external fun blendTileNative(
        tilePixels: IntArray,
        targetPixels: IntArray,
        tileW: Int,
        tileH: Int,
        targetW: Int,
        targetH: Int,
        offsetX: Int,
        offsetY: Int,
        overlapPx: Int
    )

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

    external fun detectFacesNative(
        srcPixels: IntArray,
        width: Int,
        height: Int
    ): FloatArray?

    external fun restoreFacesNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        strength: Float
    ): IntArray?

    external fun extractAlphaMatteNative(
        srcPixels: IntArray,
        coarseMask: FloatArray?,
        width: Int,
        height: Int
    ): FloatArray?

    external fun estimateDepthNative(
        srcPixels: IntArray,
        subjectMask: FloatArray?,
        width: Int,
        height: Int
    ): FloatArray?

    // 7 Populer AI Engine Native Functions (aligned 1:1 with native_enhancer.h / native_enhancer.cpp)
    external fun inpaintNative(
        srcPixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray?

    external fun applyBokehNative(
        srcPixels: IntArray,
        depthMask: FloatArray,
        width: Int,
        height: Int,
        blurRadius: Float,
        focusDepth: Float
    ): IntArray?

    external fun applyRelightingNative(
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

    external fun applyHalationNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        halationStrength: Float,
        grainAmount: Float,
        vignetteStrength: Float
    ): IntArray?

    external fun applyAnimeFilterNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        quantLevels: Int,
        edgeThreshold: Float
    ): IntArray?

    external fun colorizeImageNative(
        srcPixels: IntArray,
        width: Int,
        height: Int,
        saturation: Float
    ): IntArray?

    external fun applyStudioShadowOutlineNative(
        srcPixels: IntArray,
        mask: FloatArray,
        width: Int,
        height: Int,
        outlineWidth: Int,
        outlineColor: Int,
        shadowOffsetX: Int,
        shadowOffsetY: Int,
        shadowBlur: Float,
        bgColor: Int
    ): IntArray?

    /**
     * Initializes all 6 bundled SOTA NCNN models from APK assets:
     * 1. realesr-general-x4v3 (Crisp 4x Super-Resolution)
     * 2. realesr-general-wdn-x4v3 (Denoised 4x Super-Resolution)
     * 3. scrfd_500m_kps-opt2 (Face & 5-Point Landmark Detector)
     * 4. face_paint_512_v2 (AnimeGANv2 Portrait/Scene Stylizer)
     * 5. modnet_matting (MODNet Fine Hair Alpha Matting)
     * 6. dpt256 (Depth Anything Monocular 3D Depth Estimation)
     */
    @Synchronized
    fun initModels(context: Context): Boolean {
        if (isModelLoaded) return true

        if (!ensureNativeLoaded()) {
            Log.w(TAG, "Native library not loaded. Image enhancement will use mathematical fallbacks.")
            return false
        }

        return try {
            val assetManager = context.assets
            Log.i(TAG, "Initializing bundled SOTA NCNN model suite from assets...")
            val success = initAllModelsNative(assetManager) ||
                loadModelNative(assetManager, "models/realesr-general-x4v3.param", "models/realesr-general-x4v3.bin")
            isModelLoaded = success

            if (success) {
                Log.i(TAG, "SOTA NCNN model suite successfully loaded and ready for inference.")
            } else {
                Log.w(TAG, "NCNN asset models unavailable; C++17 SOTA DSP fallback active.")
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during initModels: ${t.message}", t)
            false
        }
    }

    /**
     * Guards against OOM when 4x upscaling an already high-resolution image.
     */
    fun capForSuperResolution(input: Bitmap, scale: Int = 4): Bitmap {
        val maxInputSide = MAX_OUTPUT_DIMENSION / scale
        val curMax = max(input.width, input.height)
        if (curMax <= maxInputSide) return input
        val ratio = maxInputSide.toFloat() / curMax.toFloat()
        val newW = max(1, (input.width * ratio).toInt())
        val newH = max(1, (input.height * ratio).toInt())
        return Bitmap.createScaledBitmap(input, newW, newH, true)
    }

    /**
     * General image enhancement supporting:
     * - MODE_SUPER_RESOLUTION (0): 4x Real-ESRGAN Super-Resolution
     * - MODE_BILATERAL_DENOISE (1): Edge-Preserving Bilateral Denoise
     * - MODE_UNSHARP_MASK (2): Adaptive Luminance Unsharp Mask
     * - MODE_COMBINED_ENHANCE (3): Denoise + 4x SR + Micro-Contrast
     * - MODE_FACE_RESTORE (4): Restorasi Wajah & Potret (SCRFD + Neural Face SR + Frequency Separation)
     */
    fun enhanceImage(input: Bitmap, mode: Int = MODE_SUPER_RESOLUTION, strength: Float = 0.8f): Bitmap {
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
                Log.w(TAG, "Processed pixels size mismatch, returning original bitmap.")
                safeInput
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in enhanceImage: ${t.message}", t)
            safeInput
        }
    }

    fun upscaleImage(input: Bitmap, strength: Float = 0.8f): Bitmap {
        return enhanceImage(input, MODE_SUPER_RESOLUTION, strength)
    }

    /**
     * Detects faces and their 5 anatomical landmarks (eyes, nose, mouth corners) using SCRFD NCNN.
     */
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
            val list = ArrayList<DetectedFaceBox>(count)
            for (i in 0 until count) {
                val base = i * 15
                val kps = FloatArray(10)
                System.arraycopy(raw, base + 5, kps, 0, 10)
                list.add(
                    DetectedFaceBox(
                        x = raw[base],
                        y = raw[base + 1],
                        width = raw[base + 2],
                        height = raw[base + 3],
                        score = raw[base + 4],
                        landmarks = kps
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.e(TAG, "detectFaces error: ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Restorasi Wajah & Potret.
     */
    fun restoreFaces(input: Bitmap, strength: Float = 0.85f): Bitmap {
        return enhanceImage(input, MODE_FACE_RESTORE, strength)
    }

    /**
     * Extracts a refined hair-accurate alpha matte in [0.0f, 1.0f] using MODNet NCNN + Fast Guided Filter.
     */
    fun extractAlphaMatte(input: Bitmap, coarseMask: FloatArray? = null): FloatArray {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val total = width * height
        val inPixels = IntArray(total)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        if (isNativeLibraryLoaded) {
            try {
                val refined = extractAlphaMatteNative(inPixels, coarseMask, width, height)
                if (refined != null && refined.size == total) return refined
            } catch (t: Throwable) {
                Log.e(TAG, "extractAlphaMatte error: ${t.message}", t)
            }
        }
        return coarseMask ?: FloatArray(total) { 1.0f }
    }

    /**
     * Estimates a continuous 3D depth map in [0.0f, 1.0f] (1.0 = closest foreground, 0.0 = farthest background)
     * using Depth Anything (dpt256 NCNN) + Geodesic 3D Relief + Guided Filter.
     */
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

        // JVM fallback: curved dome relief from subjectMask or center prior
        val fallback = FloatArray(total)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxR = max(width, height) * 0.55f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val d = kotlin.math.hypot(x - cx, y - cy) / maxR
                val dome = (1.0f - d * d).coerceIn(0.0f, 1.0f)
                val subj = subjectMask?.get(idx) ?: dome
                fallback[idx] = (0.65f * subj + 0.35f * dome).coerceIn(0.0f, 1.0f)
            }
        }
        return fallback
    }

    /**
     * Magic Eraser: Native Fast Marching Method (Telea) + Exemplar Patch Texture Inpainting.
     */
    fun inpaint(input: Bitmap, mask: Bitmap, radius: Int = 6): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height

        val scaledMask = if (mask.width != width || mask.height != height) {
            Bitmap.createScaledBitmap(mask, width, height, false)
        } else {
            mask
        }

        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        val maskPixels = IntArray(width * height)
        scaledMask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        return try {
            val resultPixels = if (isNativeLibraryLoaded) {
                inpaintNative(inPixels, maskPixels, width, height, radius)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inpaint error: ${t.message}", t)
            input
        }
    }

    /**
     * Optical Lens Bokeh Blur using on-device depth map.
     */
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
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "depthBokeh error: ${t.message}", t)
            input
        }
    }

    /**
     * 3D Studio Relighting: simulates directional light source following 3D subject curvature.
     */
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

        val r = ((lightColor shr 16) and 0xFF) / 255.0f
        val g = ((lightColor shr 8) and 0xFF) / 255.0f
        val b = (lightColor and 0xFF) / 255.0f

        return try {
            val resultPixels = if (isNativeLibraryLoaded && depthMask.size == width * height) {
                applyRelightingNative(inPixels, depthMask, width, height, lightX, lightY, lightZ, r, g, b, intensity)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "relighting3D error: ${t.message}", t)
            input
        }
    }

    /**
     * AI Anime Cel-Shading (AnimeGANv2 NCNN + Anisotropic Kuwahara + DoG Ink Contour).
     */
    fun animeCelShade(input: Bitmap, radius: Int = 4, edgeStrength: Float = 0.8f, vibrancy: Float = 1.0f): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        // Map UI radius [2..8] & edgeStrength [0.1..1.5] to native quantLevels & edgeThreshold
        val quantLevels = (10 - radius).coerceIn(4, 12)
        val edgeThreshold = (0.42f / edgeStrength.coerceIn(0.2f, 1.8f)).coerceIn(0.12f, 0.75f)

        return try {
            val resultPixels = if (isNativeLibraryLoaded) {
                applyAnimeFilterNative(inPixels, width, height, quantLevels, edgeThreshold)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "animeCelShade error: ${t.message}", t)
            input
        }
    }

    /**
     * Vintage CCD / CineStill 800T Red Halation & Film Grain.
     */
    fun vintageHalation(
        input: Bitmap,
        halationRadius: Float = 14f,
        grainStrength: Float = 0.5f,
        chromaticShift: Float = 0.6f,
        warmth: Float = 1.0f
    ): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        val halationStrength = (halationRadius / 18f).coerceIn(0.1f, 1.5f) * warmth.coerceIn(0.5f, 1.5f)
        val vignetteStrength = chromaticShift.coerceIn(0.0f, 1.2f) * 0.65f

        return try {
            val resultPixels = if (isNativeLibraryLoaded) {
                applyHalationNative(inPixels, width, height, halationStrength, grainStrength, vignetteStrength)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "vintageHalation error: ${t.message}", t)
            input
        }
    }

    /**
     * Colorize Old B&W Photos with perceptual CIE LAB skin, foliage, and sky chrominance mapping.
     */
    fun colorizeGrayscale(
        input: Bitmap,
        personMask: FloatArray,
        skinWarmth: Float = 1.0f,
        skyCyan: Float = 1.0f
    ): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        val saturation = ((skinWarmth + skyCyan) * 0.5f).coerceIn(0.2f, 2.0f)

        return try {
            val resultPixels = if (isNativeLibraryLoaded) {
                colorizeImageNative(inPixels, width, height, saturation)
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "colorizeGrayscale error: ${t.message}", t)
            input
        }
    }

    /**
     * Studio Product Drop Shadow and Exact EDT Anti-Aliased Sticker Outline.
     */
    fun studioShadowOutline(
        input: Bitmap,
        mask: FloatArray,
        shadowDx: Int = 12,
        shadowDy: Int = 20,
        shadowRadius: Int = 16,
        shadowAlpha: Float = 0.65f,
        outlineWidth: Int = 10,
        outlineColor: Int = Color.WHITE,
        bgColor: Int = Color.WHITE
    ): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        return try {
            val resultPixels = if (isNativeLibraryLoaded && mask.size == width * height) {
                applyStudioShadowOutlineNative(
                    inPixels,
                    mask,
                    width,
                    height,
                    outlineWidth,
                    outlineColor,
                    shadowDx,
                    shadowDy,
                    shadowRadius.toFloat(),
                    bgColor
                )
            } else null

            if (resultPixels != null && resultPixels.size == width * height) {
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                outBitmap
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "studioShadowOutline error: ${t.message}", t)
            input
        }
    }
}
