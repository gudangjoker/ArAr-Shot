package com.ararshot.app.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

object ImageUtils {
    private const val TAG = "ImageUtils"

    fun loadBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap {
        val inputStream1 = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open URI")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream1, null, options)
        inputStream1.close()

        var inSampleSize = 1
        val maxSide = maxOf(options.outWidth, options.outHeight)
        while (maxSide / (inSampleSize * 2) >= maxDim) {
            inSampleSize *= 2
        }

        val inputStream2 = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open URI")
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeStream(inputStream2, null, decodeOptions) ?: throw IOException("Decode failed")
        inputStream2.close()

        val currentMax = maxOf(decoded.width, decoded.height)
        if (currentMax > maxDim) {
            val scale = maxDim.toFloat() / currentMax
            val scaledW = (decoded.width * scale).toInt()
            val scaledH = (decoded.height * scale).toInt()
            return Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true)
        }
        return decoded
    }

    /**
     * Real-time hardware/canvas color grading applying exposure, contrast, saturation, temperature, and vignette.
     */
    fun applyColorGrading(
        src: Bitmap,
        exposure: Float = 0f,       // -100 to +100
        contrast: Float = 1f,       // 0.5 to 2.0
        saturation: Float = 1f,     // 0.0 to 2.0
        temperature: Float = 0f,    // -50 (cool) to +50 (warm)
        vignette: Float = 0f        // 0.0 to 1.0
    ): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Composite Color Matrix
        val cm = ColorMatrix()

        // 1. Saturation
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(saturation.coerceIn(0f, 3f))

        // 2. Contrast & Exposure
        val scale = contrast.coerceIn(0.2f, 3f)
        val translate = exposure + (128f * (1f - scale))
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // 3. Temperature (warm = more red/yellow, cool = more blue)
        val tempR = if (temperature > 0) temperature * 0.8f else 0f
        val tempB = if (temperature < 0) -temperature * 0.8f else 0f
        val tempMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, tempR,
                0f, 1f, 0f, 0f, tempR * 0.4f,
                0f, 0f, 1f, 0f, tempB,
                0f, 0f, 0f, 1f, 0f
            )
        )

        cm.postConcat(satMatrix)
        cm.postConcat(contrastMatrix)
        cm.postConcat(tempMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        // 4. Vignette effect
        if (vignette > 0.05f) {
            val w = src.width.toFloat()
            val h = src.height.toFloat()
            val radius = maxOf(w, h) * 0.75f
            val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colors = intArrayOf(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.argb((vignette.coerceIn(0f, 1f) * 200).toInt(), 0, 0, 0)
            )
            val stops = floatArrayOf(0.4f, 1.0f)
            vignettePaint.shader = RadialGradient(
                w / 2f, h / 2f, radius,
                colors, stops,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }

        return result
    }

    /**
     * Rotates bitmap by given angle in degrees.
     */
    fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Flips bitmap horizontally or vertically.
     */
    fun flipBitmap(src: Bitmap, horizontal: Boolean, vertical: Boolean): Bitmap {
        val matrix = Matrix().apply {
            postScale(
                if (horizontal) -1f else 1f,
                if (vertical) -1f else 1f
            )
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Crops bitmap to specified aspect ratio centered.
     */
    fun cropToAspectRatio(src: Bitmap, aspectX: Int, aspectY: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val targetRatio = aspectX.toFloat() / aspectY.toFloat()
        val srcRatio = srcW.toFloat() / srcH.toFloat()

        val cropW: Int
        val cropH: Int

        if (srcRatio > targetRatio) {
            cropH = srcH
            cropW = (srcH * targetRatio).toInt()
        } else {
            cropW = srcW
            cropH = (srcW / targetRatio).toInt()
        }

        val startX = (srcW - cropW) / 2
        val startY = (srcH - cropH) / 2

        return Bitmap.createBitmap(src, startX, startY, cropW, cropH)
    }

    /**
     * Saves high-res bitmap to MediaStore gallery.
     */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, title: String = "ArArShot_${System.currentTimeMillis()}"): Boolean {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArArShot")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap to gallery: ${e.message}", e)
            false
        }
    }
}
