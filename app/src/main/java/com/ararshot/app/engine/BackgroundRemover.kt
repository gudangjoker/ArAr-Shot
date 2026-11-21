package com.ararshot.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

enum class BgReplaceMode(val displayName: String) {
    TRANSPARENT("Transparan (PNG)"),
    SOLID_COLOR("Warna Polos"),
    GRADIENT_STUDIO("Gradasi"),
    BLUR_ORIGINAL("Latar Blur"),
    CUSTOM_IMAGE("Foto Galeri"),
    STUDIO_SHADOW("Bayangan"),
    STICKER_OUTLINE("Stiker"),
    COLOR_SPLASH("Latar B&W"),
    PASSPORT_RED("Merah Pasfoto"),
    PASSPORT_BLUE("Biru Pasfoto")
}

data class BgReplaceConfig(
    val mode: BgReplaceMode = BgReplaceMode.TRANSPARENT,
    val solidColor: Int = Color.WHITE,
    val gradientColors: Pair<Int, Int> = Pair(Color.parseColor("#1E293B"), Color.parseColor("#090B10")),
    val blurRadius: Float = 16f,
    val customBgBitmap: Bitmap? = null,
    val edgeThreshold: Float = 0.5f,
    val shadowDx: Int = 10,
    val shadowDy: Int = 18,
    val shadowRadius: Int = 16,
    val shadowAlpha: Float = 0.55f,
    val outlineWidth: Int = 10,
    val outlineColor: Int = Color.WHITE
)

object BackgroundRemover {
    private const val TAG = "ArArCutout"

    private val subjectSegmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        SubjectSegmentation.getClient(options)
    }

    /**
     * Runs Google ML Kit Subject Segmentation (works for people, pets, products, and general objects).
     * Returns a FloatBuffer sized exactly width * height matching the input bitmap.
     */
    private suspend fun runSubjectSegmentation(bitmap: Bitmap): FloatBuffer? = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        suspendCancellableCoroutine { continuation ->
            subjectSegmenter.process(inputImage)
                .addOnSuccessListener { result ->
                    continuation.resume(result.foregroundConfidenceMask)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "ML Kit Subject Segmentation unavailable: ${error.message}")
                    continuation.resumeWithException(error)
                }
        }
    }

    /**
     * Extracts a clean float alpha matte [0..1]:
     * 1. Runs ML Kit Subject Segmentation (full image resolution, zero buffer mismatch!).
     * 2. Checks via SCRFD whether the photo contains a human portrait.
     *    - If portrait: fuses ML Kit mask with MODNet NCNN hair matting + Fast Guided Filter.
     *    - If non-human object (product/food/pet): refines ML Kit mask with Fast Guided Filter directly
     *      without letting portrait-only MODNet erase the object!
     * 3. If Play Services is unavailable (e.g. offline/no-GMS device): falls back cleanly to MODNet offline.
     */
    suspend fun getFloatMask(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height
        var coarseMask: FloatArray? = null

        try {
            val maskBuf = runSubjectSegmentation(bitmap)
            if (maskBuf != null && maskBuf.remaining() >= total) {
                val arr = FloatArray(total)
                maskBuf.rewind()
                maskBuf.get(arr, 0, total)
                coarseMask = arr
            } else if (maskBuf != null) {
                Log.w(TAG, "Unexpected mask size ${maskBuf.remaining()} vs $total; falling back to MODNet")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Using offline MODNet fallback: ${e.message}")
        }

        val hasFace = ImageEnhancerNative.detectFaces(bitmap).isNotEmpty()
        ImageEnhancerNative.extractAlphaMatte(
            input = bitmap,
            coarseMask = coarseMask,
            isPortraitHint = hasFace || coarseMask == null
        )
    }

    suspend fun getDepthMap(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val subjectMask = getFloatMask(bitmap)
        ImageEnhancerNative.estimateDepth(bitmap, subjectMask)
    }

    suspend fun processBackground(
        source: Bitmap,
        config: BgReplaceConfig,
        cachedAlphaMatte: FloatArray? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height
        val total = width * height

        val alphaMatte = if (cachedAlphaMatte != null && cachedAlphaMatte.size == total) {
            cachedAlphaMatte
        } else {
            getFloatMask(source)
        }

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
                IntArray(total).also { gradBmp.getPixels(it, 0, width, 0, 0, width, height) }
            }
            BgReplaceMode.BLUR_ORIGINAL -> {
                val small = Bitmap.createScaledBitmap(source, max(1, width / 6), max(1, height / 6), true)
                val full = Bitmap.createScaledBitmap(small, width, height, true)
                IntArray(total).also { full.getPixels(it, 0, width, 0, 0, width, height) }
            }
            BgReplaceMode.CUSTOM_IMAGE -> {
                val custom = config.customBgBitmap
                if (custom != null) {
                    val scaled = Bitmap.createScaledBitmap(custom, width, height, true)
                    IntArray(total).also { scaled.getPixels(it, 0, width, 0, 0, width, height) }
                } else {
                    IntArray(total) { Color.WHITE }
                }
            }
            BgReplaceMode.PASSPORT_RED -> IntArray(total) { Color.parseColor("#DB1514") }
            BgReplaceMode.PASSPORT_BLUE -> IntArray(total) { Color.parseColor("#0054A6") }
            BgReplaceMode.COLOR_SPLASH -> {
                IntArray(total) { i ->
                    val p = srcPixels[i]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    val gray = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            BgReplaceMode.STUDIO_SHADOW, BgReplaceMode.STICKER_OUTLINE -> null
        }

        val threshold = config.edgeThreshold.coerceIn(0.20f, 0.80f)
        val transWidth = 0.14f
        val edge0 = (threshold - transWidth).coerceAtLeast(0.02f)
        val edge1 = (threshold + transWidth).coerceAtMost(0.98f)
        val invSpan = 1.0f / (edge1 - edge0)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val confidence = alphaMatte[i]
                val origPixel = srcPixels[i]
                val origA = (origPixel ushr 24) and 0xFF
                var fgR = (origPixel ushr 16) and 0xFF
                var fgG = (origPixel ushr 8) and 0xFF
                var fgB = origPixel and 0xFF

                val t = ((confidence - edge0) * invSpan).coerceIn(0.0f, 1.0f)
                val alphaFactor = t * t * (3.0f - 2.0f * t)

                // Color decontamination on semi-transparent boundary pixels to remove background fringe
                if (alphaFactor in 0.05f..0.90f) {
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
                            val decontamW = (1.0f - alphaFactor) * 0.55f
                            fgR = (fgR * (1.0f - decontamW) + ((cp ushr 16) and 0xFF) * decontamW).toInt().coerceIn(0, 255)
                            fgG = (fgG * (1.0f - decontamW) + ((cp ushr 8) and 0xFF) * decontamW).toInt().coerceIn(0, 255)
                            fgB = (fgB * (1.0f - decontamW) + (cp and 0xFF) * decontamW).toInt().coerceIn(0, 255)
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
                    val bgF = 1.0f - alphaFactor
                    val finalR = (fgR * alphaFactor + bgR * bgF).toInt().coerceIn(0, 255)
                    val finalG = (fgG * alphaFactor + bgG * bgF).toInt().coerceIn(0, 255)
                    val finalB = (fgB * alphaFactor + bgB * bgF).toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        output
    }

    suspend fun renderTextBehindSubject(
        baseImage: Bitmap,
        textConfig: TextOverlayConfig,
        cachedAlphaMatte: FloatArray? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = baseImage.width
        val height = baseImage.height
        val total = width * height

        val alphaMatte = if (cachedAlphaMatte != null && cachedAlphaMatte.size == total) {
            cachedAlphaMatte
        } else {
            getFloatMask(baseImage)
        }

        val withText = TextStyler.drawTextOnBitmap(baseImage, textConfig)
        val basePixels = IntArray(total)
        val textPixels = IntArray(total)
        baseImage.getPixels(basePixels, 0, width, 0, 0, width, height)
        withText.getPixels(textPixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(total)
        for (i in 0 until total) {
            val t = ((alphaMatte[i] - 0.35f) / 0.30f).coerceIn(0.0f, 1.0f)
            val fgF = t * t * (3.0f - 2.0f * t)
            val bgF = 1.0f - fgF

            val fgP = basePixels[i]
            val bgP = textPixels[i]
            val r = (((fgP ushr 16) and 0xFF) * fgF + ((bgP ushr 16) and 0xFF) * bgF).toInt().coerceIn(0, 255)
            val g = (((fgP ushr 8) and 0xFF) * fgF + ((bgP ushr 8) and 0xFF) * bgF).toInt().coerceIn(0, 255)
            val b = ((fgP and 0xFF) * fgF + (bgP and 0xFF) * bgF).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, width, 0, 0, width, height)
        }
    }
}
