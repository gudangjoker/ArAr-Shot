package com.ararshot.app.engine

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.ararshot.app.model.GalleryPhoto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

object ImageUtils {
    private const val TAG = "ArArImageUtils"

    /**
     * Loads a Bitmap from a Content/File URI with full HEIC/WebP/JPG/PNG support
     * (via ImageDecoder on Android 9+ and BitmapFactory + EXIF fallback on older Android).
     */
    fun loadBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxDim && maxDim > 0) {
                        val ratio = maxDim.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            max(1, (w * ratio).toInt()),
                            max(1, (h * ratio).toInt())
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder fallback to BitmapFactory: ${e.message}")
            }
        }

        val exifOrientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Tidak dapat membuka file gambar")

        var inSampleSize = 1
        val maxSide = max(options.outWidth, options.outHeight)
        while (maxSide / (inSampleSize * 2) >= maxDim) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IOException("Gagal membaca file gambar")

        val oriented = applyExifOrientation(decoded, exifOrientation)
        val currentMax = max(oriented.width, oriented.height)
        return if (currentMax > maxDim) {
            val scale = maxDim.toFloat() / currentMax
            Bitmap.createScaledBitmap(
                oriented,
                max(1, (oriented.width * scale).toInt()),
                max(1, (oriented.height * scale).toInt()),
                true
            )
        } else {
            oriented
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    /**
     * Queries recent photos from the device MediaStore for the Home quick gallery grid.
     */
    fun queryRecentPhotos(context: Context, limit: Int = 60): List<GalleryPhoto> {
        val list = mutableListOf<GalleryPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && list.size < limit) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Foto"
                    val date = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    list.add(GalleryPhoto(id = id, uri = uri, displayName = name, dateAddedSec = date))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryRecentPhotos skipped: ${e.message}")
        }
        return list
    }

    fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun flipBitmap(src: Bitmap, horizontal: Boolean, vertical: Boolean): Bitmap {
        val matrix = Matrix().apply {
            postScale(if (horizontal) -1f else 1f, if (vertical) -1f else 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Applies straightened rotation (auto-zoomed to avoid black corner triangles) and normalized crop rect [0..1].
     */
    fun cropAndStraighten(src: Bitmap, cropNormalized: RectF, straightenDegrees: Float): Bitmap {
        var working = src
        if (kotlin.math.abs(straightenDegrees) > 0.05f) {
            val rad = Math.toRadians(kotlin.math.abs(straightenDegrees.toDouble()))
            val w = src.width.toDouble()
            val h = src.height.toDouble()
            val cosA = kotlin.math.cos(rad)
            val sinA = kotlin.math.sin(rad)
            // Compute largest inscribed rectangle scale to eliminate black corners
            val scale = max(
                (w * cosA + h * sinA) / w,
                (h * cosA + w * sinA) / h
            ).toFloat()

            val rotated = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rotated)
            val matrix = Matrix().apply {
                postTranslate(-src.width / 2f, -src.height / 2f)
                postRotate(straightenDegrees)
                postScale(scale, scale)
                postTranslate(src.width / 2f, src.height / 2f)
            }
            canvas.drawBitmap(src, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            working = rotated
        }

        val left = (cropNormalized.left.coerceIn(0f, 0.95f) * working.width).toInt()
        val top = (cropNormalized.top.coerceIn(0f, 0.95f) * working.height).toInt()
        val right = (cropNormalized.right.coerceIn(0.05f, 1f) * working.width).toInt()
        val bottom = (cropNormalized.bottom.coerceIn(0.05f, 1f) * working.height).toInt()
        val cw = (right - left).coerceIn(1, working.width - left)
        val ch = (bottom - top).coerceIn(1, working.height - top)

        if (left == 0 && top == 0 && cw == working.width && ch == working.height) return working
        return Bitmap.createBitmap(working, left, top, cw, ch)
    }

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
        val startX = ((srcW - cropW) / 2).coerceAtLeast(0)
        val startY = ((srcH - cropH) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, startX, startY, cropW.coerceAtMost(srcW), cropH.coerceAtMost(srcH))
    }

    fun resizeToMaxEdge(src: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdgePx || maxEdgePx <= 0) return src
        val ratio = maxEdgePx.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * ratio).toInt()),
            max(1, (src.height * ratio).toInt()),
            true
        )
    }

    fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val w = bitmap.width
        val h = bitmap.height
        val stepX = max(1, w / 16)
        val stepY = max(1, h / 16)
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                if (alpha < 250) return true
            }
        }
        return false
    }

    fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        title: String = "ArArShot_${System.currentTimeMillis()}",
        forcePng: Boolean = false,
        jpegQuality: Int = 95
    ): Boolean {
        return try {
            val isTransparent = forcePng || hasTransparency(bitmap)
            val ext = if (isTransparent) "png" else "jpg"
            val mimeType = if (isTransparent) "image/png" else "image/jpeg"
            val format = if (isTransparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (isTransparent) 100 else jpegQuality.coerceIn(70, 100)

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArArShot")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(format, quality, out)
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
            Log.e(TAG, "saveBitmapToGallery error: ${e.message}", e)
            false
        }
    }

    fun createShareableUri(context: Context, bitmap: Bitmap, forcePng: Boolean = false): Uri? {
        return try {
            val isTransparent = forcePng || hasTransparency(bitmap)
            val ext = if (isTransparent) "png" else "jpg"
            val format = if (isTransparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val outFile = File(dir, "ArArShot_${System.currentTimeMillis()}.$ext")
            FileOutputStream(outFile).use { bitmap.compress(format, if (isTransparent) 100 else 95, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        } catch (e: Exception) {
            Log.e(TAG, "createShareableUri error: ${e.message}", e)
            null
        }
    }

    fun createCameraOutputUri(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "CAP_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    fun addRetroDateStamp(
        source: Bitmap,
        dateText: String = "'98 10 14",
        color: Int = android.graphics.Color.parseColor("#FF8C00")
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val width = result.width.toFloat()
        val height = result.height.toFloat()
        val fontSize = (min(width, height) * 0.042f).coerceIn(22f, 80f)
        val marginX = width * 0.05f
        val marginY = height * 0.05f

        val paintGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = fontSize
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.RIGHT
            setShadowLayer(fontSize * 0.35f, 0f, 0f, color)
        }
        val paintText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = fontSize
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        canvas.drawText(dateText, width - marginX, height - marginY, paintGlow)
        canvas.drawText(dateText, width - marginX, height - marginY, paintText)
        return result
    }
}
