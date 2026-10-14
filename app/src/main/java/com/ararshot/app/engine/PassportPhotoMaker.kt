package com.ararshot.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PassportSize(val displayName: String, val ratioW: Int, val ratioH: Int, val targetW: Int, val targetH: Int) {
    SIZE_2X3("Pasfoto 2x3 cm", 2, 3, 600, 900),
    SIZE_3X4("Pasfoto 3x4 cm", 3, 4, 600, 800),
    SIZE_4X6("Pasfoto 4x6 cm", 2, 3, 800, 1200),
    VISA_2X2("Visa AS / LN (2x2 inch)", 1, 1, 800, 800)
}

enum class PassportBgColor(val displayName: String, val colorHex: String) {
    INDONESIA_RED("Merah Resmi (#DB1514)", "#DB1514"),
    INDONESIA_BLUE("Biru Resmi (#0054A6)", "#0054A6"),
    WHITE_VISA("Putih Bersih (#FFFFFF)", "#FFFFFF")
}

object PassportPhotoMaker {
    /**
     * Generates a single official passport photo with clean solid background and proper biometric aspect ratio.
     */
    suspend fun generateSinglePassport(
        source: Bitmap,
        size: PassportSize,
        bgColor: PassportBgColor
    ): Bitmap = withContext(Dispatchers.Default) {
        val colorInt = Color.parseColor(bgColor.colorHex)
        val config = BgReplaceConfig(
            mode = BgReplaceMode.SOLID_COLOR,
            solidColor = colorInt
        )
        val withBg = BackgroundRemover.processBackground(source, config)

        // Crop to target aspect ratio, centering on head (upper-middle)
        val srcW = withBg.width
        val srcH = withBg.height
        val targetRatio = size.ratioW.toFloat() / size.ratioH.toFloat()

        var cropW = srcW
        var cropH = (cropW / targetRatio).toInt()

        if (cropH > srcH) {
            cropH = srcH
            cropW = (cropH * targetRatio).toInt()
        }

        // Bias crop towards top to keep the subject's head well framed
        val cropX = ((srcW - cropW) / 2).coerceAtLeast(0)
        val cropY = ((srcH - cropH) * 0.25f).toInt().coerceIn(0, (srcH - cropH).coerceAtLeast(0))

        val cropped = Bitmap.createBitmap(withBg, cropX, cropY, cropW, cropH)
        Bitmap.createScaledBitmap(cropped, size.targetW, size.targetH, true)
    }

    /**
     * Generates a 4R (1200x1800 px at 300 DPI) printable grid sheet containing multiple copies
     * of the passport photo, formatted with cutting border guidelines for print shops.
     */
    suspend fun generate4RPrintSheet(
        singlePassport: Bitmap,
        size: PassportSize
    ): Bitmap = withContext(Dispatchers.Default) {
        // Standard 4R paper: 4x6 inches @ 300 DPI = 1200 x 1800 px (Portrait)
        val sheetW = 1200
        val sheetH = 1800
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)

        // Fill white paper background
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }

        // Determine grid configuration based on size
        val (cols, rows) = when (size) {
            PassportSize.SIZE_2X3 -> Pair(3, 4) // 12 photos
            PassportSize.SIZE_3X4 -> Pair(2, 4) // 8 photos
            PassportSize.SIZE_4X6 -> Pair(2, 2) // 4 photos
            PassportSize.VISA_2X2 -> Pair(2, 3) // 6 photos
        }

        val marginX = 60f
        val marginY = 80f
        val gapX = 30f
        val gapY = 30f

        val totalAvailW = sheetW - (2 * marginX) - ((cols - 1) * gapX)
        val totalAvailH = sheetH - (2 * marginY) - ((rows - 1) * gapY) - 50f // Leave room for footer text

        val cellW = (totalAvailW / cols)
        val cellH = (totalAvailH / rows)

        // Fit photo inside cell preserving aspect ratio
        val scale = minOf(cellW / singlePassport.width, cellH / singlePassport.height)
        val drawW = singlePassport.width * scale
        val drawH = singlePassport.height * scale

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = marginX + c * (cellW + gapX) + (cellW - drawW) / 2f
                val top = marginY + r * (cellH + gapY) + (cellH - drawH) / 2f
                val dstRect = RectF(left, top, left + drawW, top + drawH)

                canvas.drawBitmap(singlePassport, null, dstRect, null)
                // Draw cutting guideline border
                canvas.drawRect(dstRect, borderPaint)
            }
        }

        // Footer imprint
        canvas.drawText(
            "ArAr Shot Pro - Lembar Cetak 4R Resmi (${size.displayName}) - 300 DPI",
            sheetW / 2f,
            sheetH - 35f,
            textPaint
        )

        sheet
    }
}
