package com.ararshot.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class BgReplaceMode(val displayName: String) {
    TRANSPARENT("Hapus Background (PNG Transparan)"),
    SOLID_COLOR("Warna Studio Solid"),
    GRADIENT_STUDIO("Gradien Lighting Studio"),
    BLUR_ORIGINAL("DSLR Bokeh Blur"),
    CUSTOM_IMAGE("Ganti Foto dari Galeri"),
    STUDIO_SHADOW("3D Studio Product Drop Shadow"),
    STICKER_OUTLINE("Stiker Outline Putih (Photoroom Style)"),
    COLOR_SPLASH("Splash Color (BG Hitam-Putih)"),
    PASSPORT_RED("Pasfoto Merah Resmi (#DB1514)"),
    PASSPORT_BLUE("Pasfoto Biru Resmi (#0054A6)")
}

data class BgReplaceConfig(
    val mode: BgReplaceMode = BgReplaceMode.TRANSPARENT,
    val solidColor: Int = Color.WHITE,
    val gradientColors: Pair<Int, Int> = Pair(Color.DKGRAY, Color.BLACK),
    val blurRadius: Float = 15f,
    val customBgBitmap: Bitmap? = null,
    val edgeThreshold: Float = 0.5f,
    val shadowDx: Int = 12,
    val shadowDy: Int = 22,
    val shadowRadius: Int = 16,
    val shadowAlpha: Float = 0.65f,
    val outlineWidth: Int = 10,
    val outlineColor: Int = Color.WHITE
)

object BackgroundRemover {
    private const val TAG = "BackgroundRemover"

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    /**
     * Executes AI on-device segmentation and produces a raw foreground confidence buffer.
     */
    suspend fun segmentImage(bitmap: Bitmap): ByteBuffer = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        suspendCancellableCoroutine { continuation ->
            segmenter.process(inputImage)
                .addOnSuccessListener { mask ->
                    continuation.resume(mask.buffer)
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "ML Kit segmentation error: ${error.message}", error)
                    continuation.resumeWithException(error)
                }
        }
    }

    /**
     * Extracts a hair-refined float alpha matte [0.0f, 1.0f] by fusing ML Kit Subject Segmentation
     * with MODNet NCNN Alpha Matting and O(N) Fast Guided Image Filtering.
     */
    suspend fun getFloatMask(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height
        val rawFloats = FloatArray(total)

        try {
            val maskBuffer = segmentImage(bitmap)
            maskBuffer.rewind()
            for (i in 0 until total) {
                rawFloats[i] = maskBuffer.float
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit segmentation fallback active: ${e.message}")
            val cx = width * 0.5f
            val cy = height * 0.5f
            val maxR = max(width, height) * 0.42f
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val dist = hypot(x - cx, y - cy)
                    rawFloats[y * width + x] = (1.0f - (dist / maxR)).coerceIn(0.0f, 1.0f)
                }
            }
        }

        // Refine coarse mask with MODNet NCNN + Fast Guided Image Filter for fine hair strands
        ImageEnhancerNative.extractAlphaMatte(bitmap, rawFloats)
    }

    /**
     * Computes a continuous 3D Monocular Depth Map [0.0f, 1.0f] using Depth Anything (dpt256 NCNN)
     * fused with subject segmentation and 3D geodesic curvature.
     */
    suspend fun getDepthMap(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val subjectMask = getFloatMask(bitmap)
        ImageEnhancerNative.estimateDepth(bitmap, subjectMask)
    }

    /**
     * Removes or replaces the background of an image using MODNet + Guided Filter Alpha Matting
     * and Foreground Color Decontamination.
     */
    suspend fun processBackground(
        source: Bitmap,
        config: BgReplaceConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height
        val total = width * height

        val alphaMatte = getFloatMask(source)

        if (config.mode == BgReplaceMode.STUDIO_SHADOW || config.mode == BgReplaceMode.STICKER_OUTLINE) {
            val outlineW = if (config.mode == BgReplaceMode.STICKER_OUTLINE) config.outlineWidth else 0
            return@withContext ImageEnhancerNative.studioShadowOutline(
                input = source,
                mask = alphaMatte,
                shadowDx = config.shadowDx,
                shadowDy = config.shadowDy,
                shadowRadius = config.shadowRadius,
                shadowAlpha = config.shadowAlpha,
                outlineWidth = outlineW,
                outlineColor = config.outlineColor,
                bgColor = config.solidColor
            )
        }

        if (config.mode == BgReplaceMode.BLUR_ORIGINAL && ImageEnhancerNative.isNativeLoaded()) {
            return@withContext ImageEnhancerNative.depthBokeh(
                input = source,
                depthMask = alphaMatte,
                blurRadius = config.blurRadius.coerceIn(4f, 32f),
                focusDepth = 1.0f
            )
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setHasAlpha(config.mode == BgReplaceMode.TRANSPARENT)

        val srcPixels = IntArray(total)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(total)

        val bgPixels: IntArray? = when (config.mode) {
            BgReplaceMode.TRANSPARENT -> null
            BgReplaceMode.SOLID_COLOR -> IntArray(total) { config.solidColor }
            BgReplaceMode.GRADIENT_STUDIO -> {
                val gradBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(gradBmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    config.gradientColors.first, config.gradientColors.second,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                val bg = IntArray(total)
                gradBmp.getPixels(bg, 0, width, 0, 0, width, height)
                bg
            }
            BgReplaceMode.BLUR_ORIGINAL -> {
                val blurredScaled = Bitmap.createScaledBitmap(source, max(1, width / 6), max(1, height / 6), true)
                val blurredFull = Bitmap.createScaledBitmap(blurredScaled, width, height, true)
                val bg = IntArray(total)
                blurredFull.getPixels(bg, 0, width, 0, 0, width, height)
                bg
            }
            BgReplaceMode.CUSTOM_IMAGE -> {
                if (config.customBgBitmap != null) {
                    val scaledBg = Bitmap.createScaledBitmap(config.customBgBitmap, width, height, true)
                    val bg = IntArray(total)
                    scaledBg.getPixels(bg, 0, width, 0, 0, width, height)
                    bg
                } else {
                    IntArray(total) { Color.WHITE }
                }
            }
            BgReplaceMode.PASSPORT_RED -> {
                val red = Color.parseColor("#DB1514")
                IntArray(total) { red }
            }
            BgReplaceMode.PASSPORT_BLUE -> {
                val blue = Color.parseColor("#0054A6")
                IntArray(total) { blue }
            }
            BgReplaceMode.COLOR_SPLASH -> {
                val bg = IntArray(total)
                for (i in 0 until total) {
                    val p = srcPixels[i]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                    bg[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
                bg
            }
            BgReplaceMode.STUDIO_SHADOW,
            BgReplaceMode.STICKER_OUTLINE -> null
        }

        val threshold = config.edgeThreshold.coerceIn(0.15f, 0.85f)
        val band = 0.28f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val confidence = alphaMatte[i]
                val origPixel = srcPixels[i]
                val origA = (origPixel ushr 24) and 0xFF
                var fgR = (origPixel ushr 16) and 0xFF
                var fgG = (origPixel ushr 8) and 0xFF
                var fgB = origPixel and 0xFF

                val alphaFactor = when {
                    confidence >= threshold + band -> 1.0f
                    confidence <= threshold - band -> 0.0f
                    else -> ((confidence - (threshold - band)) / (2.0f * band)).coerceIn(0.0f, 1.0f)
                }

                // Foreground Color Decontamination on semi-transparent hair/boundary pixels
                if (alphaFactor in 0.05f..0.92f) {
                    // Sample nearby confident foreground pixel within a 4px cross to remove background color spill
                    val step = 3
                    val candidates = intArrayOf(
                        y * width + min(width - 1, x + step),
                        y * width + max(0, x - step),
                        min(height - 1, y + step) * width + x,
                        max(0, y - step) * width + x
                    )
                    for (cIdx in candidates) {
                        if (alphaMatte[cIdx] > confidence + 0.15f) {
                            val cp = srcPixels[cIdx]
                            val cr = (cp ushr 16) and 0xFF
                            val cg = (cp ushr 8) and 0xFF
                            val cb = cp and 0xFF
                            val decontamW = (1.0f - alphaFactor) * 0.55f
                            fgR = (fgR * (1.0f - decontamW) + cr * decontamW).toInt().coerceIn(0, 255)
                            fgG = (fgG * (1.0f - decontamW) + cg * decontamW).toInt().coerceIn(0, 255)
                            fgB = (fgB * (1.0f - decontamW) + cb * decontamW).toInt().coerceIn(0, 255)
                            break
                        }
                    }
                }

                if (config.mode == BgReplaceMode.TRANSPARENT) {
                    val newAlpha = (origA * alphaFactor).toInt().coerceIn(0, 255)
                    outPixels[i] = (newAlpha shl 24) or (fgR shl 16) or (fgG shl 8) or fgB
                } else {
                    val bgPixel = bgPixels?.get(i) ?: Color.WHITE
                    val bgR = (bgPixel ushr 16) and 0xFF
                    val bgG = (bgPixel ushr 8) and 0xFF
                    val bgB = bgPixel and 0xFF

                    val fgF = alphaFactor
                    val bgF = 1.0f - fgF

                    val finalR = (fgR * fgF + bgR * bgF).toInt().coerceIn(0, 255)
                    val finalG = (fgG * fgF + bgG * bgF).toInt().coerceIn(0, 255)
                    val finalB = (fgB * fgF + bgB * bgF).toInt().coerceIn(0, 255)

                    outPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        output
    }

    /**
     * Text Behind Subject: Sandwich Compositing
     * Layer 1: Background (baseImage)
     * Layer 2: Styled Typography Text
     * Layer 3: Foreground Subject Cutout (with Guided Filter hair-refined edge blending)
     */
    suspend fun renderTextBehindSubject(
        baseImage: Bitmap,
        textConfig: TextOverlayConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = baseImage.width
        val height = baseImage.height
        val total = width * height

        val mask = getFloatMask(baseImage)
        val textLayer = TextStyler.drawTextOnBitmap(baseImage, textConfig)

        val composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val origPixels = IntArray(total)
        baseImage.getPixels(origPixels, 0, width, 0, 0, width, height)

        val textPixels = IntArray(total)
        textLayer.getPixels(textPixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(total)

        for (i in 0 until total) {
            val conf = mask[i]
            val alphaFactor = when {
                conf >= 0.65f -> 1.0f
                conf <= 0.35f -> 0.0f
                else -> (conf - 0.35f) / 0.30f
            }

            if (alphaFactor >= 0.99f) {
                outPixels[i] = origPixels[i]
            } else if (alphaFactor <= 0.01f) {
                outPixels[i] = textPixels[i]
            } else {
                val origP = origPixels[i]
                val textP = textPixels[i]

                val oR = (origP ushr 16) and 0xFF
                val oG = (origP ushr 8) and 0xFF
                val oB = origP and 0xFF

                val tR = (textP ushr 16) and 0xFF
                val tG = (textP ushr 8) and 0xFF
                val tB = textP and 0xFF

                val fR = (oR * alphaFactor + tR * (1.0f - alphaFactor)).toInt().coerceIn(0, 255)
                val fG = (oG * alphaFactor + tG * (1.0f - alphaFactor)).toInt().coerceIn(0, 255)
                val fB = (oB * alphaFactor + tB * (1.0f - alphaFactor)).toInt().coerceIn(0, 255)

                outPixels[i] = (0xFF shl 24) or (fR shl 16) or (fG shl 8) or fB
            }
        }

        composite.setPixels(outPixels, 0, width, 0, 0, width, height)
        composite
    }
}
