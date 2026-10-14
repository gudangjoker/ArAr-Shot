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
import kotlin.math.max

enum class BgReplaceMode(val displayName: String) {
    TRANSPARENT("Hapus Background (PNG Transparan)"),
    SOLID_COLOR("Warna Studio Solid"),
    GRADIENT_STUDIO("Gradien Lighting Studio"),
    BLUR_ORIGINAL("DSLR Bokeh Blur"),
    CUSTOM_IMAGE("Ganti Foto dari Galeri")
}

data class BgReplaceConfig(
    val mode: BgReplaceMode = BgReplaceMode.TRANSPARENT,
    val solidColor: Int = Color.WHITE,
    val gradientColors: Pair<Int, Int> = Pair(Color.DKGRAY, Color.BLACK),
    val blurRadius: Float = 15f,
    val customBgBitmap: Bitmap? = null,
    val edgeThreshold: Float = 0.5f
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
     * Executes AI on-device segmentation and produces a high-quality foreground mask buffer.
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
     * Removes or replaces the background of an image using the on-device AI segmentation mask.
     */
    suspend fun processBackground(
        source: Bitmap,
        config: BgReplaceConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height

        val maskBuffer = try {
            segmentImage(source)
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}")
            return@withContext source.copy(Bitmap.Config.ARGB_8888, true)
        }

        maskBuffer.rewind()

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val srcPixels = IntArray(width * height)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(width * height)

        // Background layer preparation
        val bgPixels: IntArray? = when (config.mode) {
            BgReplaceMode.TRANSPARENT -> null
            BgReplaceMode.SOLID_COLOR -> {
                val bg = IntArray(width * height)
                bg.fill(config.solidColor)
                bg
            }
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
                val bg = IntArray(width * height)
                gradBmp.getPixels(bg, 0, width, 0, 0, width, height)
                bg
            }
            BgReplaceMode.BLUR_ORIGINAL -> {
                val blurredScaled = Bitmap.createScaledBitmap(source, max(1, width / 6), max(1, height / 6), true)
                val blurredFull = Bitmap.createScaledBitmap(blurredScaled, width, height, true)
                val bg = IntArray(width * height)
                blurredFull.getPixels(bg, 0, width, 0, 0, width, height)
                bg
            }
            BgReplaceMode.CUSTOM_IMAGE -> {
                if (config.customBgBitmap != null) {
                    val scaledBg = Bitmap.createScaledBitmap(config.customBgBitmap, width, height, true)
                    val bg = IntArray(width * height)
                    scaledBg.getPixels(bg, 0, width, 0, 0, width, height)
                    bg
                } else {
                    val bg = IntArray(width * height)
                    bg.fill(Color.WHITE)
                    bg
                }
            }
        }

        val threshold = config.edgeThreshold

        for (i in 0 until (width * height)) {
            val confidence = maskBuffer.float
            val origPixel = srcPixels[i]
            val origA = (origPixel ushr 24) and 0xFF
            val origR = (origPixel ushr 16) and 0xFF
            val origG = (origPixel ushr 8) and 0xFF
            val origB = origPixel and 0xFF

            val alphaFactor = when {
                confidence >= threshold + 0.12f -> 1.0f
                confidence <= threshold - 0.12f -> 0.0f
                else -> (confidence - (threshold - 0.12f)) / 0.24f
            }

            if (config.mode == BgReplaceMode.TRANSPARENT) {
                val newAlpha = (origA * alphaFactor).toInt().coerceIn(0, 255)
                outPixels[i] = (newAlpha shl 24) or (origR shl 16) or (origG shl 8) or origB
            } else {
                val bgPixel = bgPixels?.get(i) ?: Color.WHITE
                val bgR = (bgPixel ushr 16) and 0xFF
                val bgG = (bgPixel ushr 8) and 0xFF
                val bgB = bgPixel and 0xFF

                val fgF = alphaFactor
                val bgF = 1.0f - fgF

                val finalR = (origR * fgF + bgR * bgF).toInt().coerceIn(0, 255)
                val finalG = (origG * fgF + bgG * bgF).toInt().coerceIn(0, 255)
                val finalB = (origB * fgF + bgB * bgF).toInt().coerceIn(0, 255)

                outPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        output
    }
}
