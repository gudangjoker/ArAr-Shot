package com.ararshot.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PassportSize(val displayName: String, val ratioW: Int, val ratioH: Int, val targetW: Int, val targetH: Int) {
    SIZE_2X3("2×3 cm", 2, 3, 600, 900),
    SIZE_3X4("3×4 cm", 3, 4, 600, 800),
    SIZE_4X6("4×6 cm", 2, 3, 800, 1200),
    VISA_2X2("2×2 inci", 1, 1, 800, 800)
}

enum class PassportBgColor(val displayName: String, val colorHex: String) {
    INDONESIA_RED("Merah", "#DB1514"),
    INDONESIA_BLUE("Biru", "#0054A6"),
    WHITE_VISA("Putih", "#FFFFFF")
}

object PassportPhotoMaker {
    suspend fun generateSinglePassport(
        source: Bitmap,
        size: PassportSize,
        bgColor: PassportBgColor,
        cachedAlphaMatte: FloatArray? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val colorInt = Color.parseColor(bgColor.colorHex)
        val config = BgReplaceConfig(
            mode = BgReplaceMode.SOLID_COLOR,
            solidColor = colorInt
        )
        val withBg = BackgroundRemover.processBackground(source, config, cachedAlphaMatte)

        val srcW = withBg.width
        val srcH = withBg.height
        val targetRatio = size.ratioW.toFloat() / size.ratioH.toFloat()

        var cropW = srcW
        var cropH = (cropW / targetRatio).toInt()
        if (cropH > srcH) {
            cropH = srcH
            cropW = (cropH * targetRatio).toInt()
        }
        cropW = cropW.coerceIn(1, srcW)
        cropH = cropH.coerceIn(1, srcH)

        val faces = ImageEnhancerNative.detectFaces(source)
        val primaryFace = faces.maxByOrNull { it.width * it.height }

        val cropX: Int
        val cropY: Int
        if (primaryFace != null) {
            val lEyeX = primaryFace.landmarks[0]
            val lEyeY = primaryFace.landmarks[1]
            val rEyeX = primaryFace.landmarks[2]
            val rEyeY = primaryFace.landmarks[3]
            val eyeCenterX = if (lEyeX > 0f && rEyeX > 0f) (lEyeX + rEyeX) * 0.5f else primaryFace.x + primaryFace.width * 0.5f
            val eyeCenterY = if (lEyeY > 0f && rEyeY > 0f) (lEyeY + rEyeY) * 0.5f else primaryFace.y + primaryFace.height * 0.40f
            cropX = (eyeCenterX - cropW * 0.5f).toInt().coerceIn(0, (srcW - cropW).coerceAtLeast(0))
            cropY = (eyeCenterY - cropH * 0.42f).toInt().coerceIn(0, (srcH - cropH).coerceAtLeast(0))
        } else {
            cropX = ((srcW - cropW) / 2).coerceAtLeast(0)
            cropY = ((srcH - cropH) * 0.22f).toInt().coerceIn(0, (srcH - cropH).coerceAtLeast(0))
        }

        val cropped = Bitmap.createBitmap(withBg, cropX, cropY, cropW, cropH)
        Bitmap.createScaledBitmap(cropped, size.targetW, size.targetH, true)
    }

    suspend fun generate4RPrintSheet(
        singlePassport: Bitmap,
        size: PassportSize
    ): Bitmap = withContext(Dispatchers.Default) {
        val sheetW = 1200
        val sheetH = 1800
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }

        val (cols, rows) = when (size) {
            PassportSize.SIZE_2X3 -> Pair(3, 4)
            PassportSize.SIZE_3X4 -> Pair(2, 4)
            PassportSize.SIZE_4X6 -> Pair(2, 2)
            PassportSize.VISA_2X2 -> Pair(2, 3)
        }

        val marginX = 60f
        val marginY = 80f
        val gapX = 30f
        val gapY = 30f

        val totalAvailW = sheetW - (2 * marginX) - ((cols - 1) * gapX)
        val totalAvailH = sheetH - (2 * marginY) - ((rows - 1) * gapY) - 50f
        val cellW = totalAvailW / cols
        val cellH = totalAvailH / rows

        val scale = minOf(cellW / singlePassport.width, cellH / singlePassport.height)
        val drawW = singlePassport.width * scale
        val drawH = singlePassport.height * scale

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = marginX + c * (cellW + gapX) + (cellW - drawW) / 2f
                val top = marginY + r * (cellH + gapY) + (cellH - drawH) / 2f
                val dstRect = RectF(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(singlePassport, null, dstRect, null)
                canvas.drawRect(dstRect, borderPaint)
            }
        }

        canvas.drawText(
            "ArAr Shot • Lembar Cetak 4R (${size.displayName})",
            sheetW / 2f,
            sheetH - 34f,
            textPaint
        )
        sheet
    }
}
