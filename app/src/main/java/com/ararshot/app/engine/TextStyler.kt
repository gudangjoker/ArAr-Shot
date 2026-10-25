package com.ararshot.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

enum class TextFontFamily(val displayName: String, val typeface: Typeface) {
    SANS_SERIF("Sans Modern", Typeface.SANS_SERIF),
    SERIF("Serif Klasik", Typeface.SERIF),
    MONOSPACE("Monospace Tebal", Typeface.MONOSPACE),
    BOLD_IMPACT("Tebal Tegas (Impact)", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)),
    CURSIVE("Kaligrafi Elegan", Typeface.create("cursive", Typeface.BOLD_ITALIC))
}

enum class TextStyleModel(val displayName: String) {
    CLEAN("Bersih Minimalis"),
    SHADOW_3D("Bayangan 3D Dalam"),
    NEON_GLOW("Kilau Neon Menyala"),
    OUTLINE_STROKE("Garis Tepi Stiker"),
    BADGE_PILL("Kotak Lencana (Badge)")
}

data class TextOverlayConfig(
    val text: String = "ArAr Shot Pro",
    val font: TextFontFamily = TextFontFamily.BOLD_IMPACT,
    val styleModel: TextStyleModel = TextStyleModel.NEON_GLOW,
    val textColor: Int = Color.WHITE,
    val accentColor: Int = Color.CYAN,
    val fontSizeSp: Float = 36f,
    val positionXFraction: Float = 0.5f,
    val positionYFraction: Float = 0.8f,
    val rotationDegrees: Float = 0f
)

object TextStyler {
    /**
     * Burns styled text onto target bitmap and returns composite result.
     */
    fun drawTextOnBitmap(source: Bitmap, config: TextOverlayConfig): Bitmap {
        if (config.text.isBlank()) return source

        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val width = result.width.toFloat()
        val height = result.height.toFloat()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = config.font.typeface
            textSize = config.fontSizeSp * (width / 400f)
            textAlign = Paint.Align.CENTER
        }

        val posX = width * config.positionXFraction
        val posY = height * config.positionYFraction

        canvas.save()
        canvas.rotate(config.rotationDegrees, posX, posY)

        val bounds = Rect()
        textPaint.getTextBounds(config.text, 0, config.text.length, bounds)

        when (config.styleModel) {
            TextStyleModel.CLEAN -> {
                textPaint.color = config.textColor
                canvas.drawText(config.text, posX, posY, textPaint)
            }
            TextStyleModel.SHADOW_3D -> {
                val shadowPaint = Paint(textPaint).apply {
                    color = Color.argb(180, 0, 0, 0)
                    setShadowLayer(16f, 8f, 8f, Color.BLACK)
                }
                canvas.drawText(config.text, posX, posY, shadowPaint)

                textPaint.color = config.textColor
                canvas.drawText(config.text, posX, posY, textPaint)
            }
            TextStyleModel.NEON_GLOW -> {
                val glowPaint = Paint(textPaint).apply {
                    color = config.accentColor
                    setShadowLayer(26f, 0f, 0f, config.accentColor)
                }
                canvas.drawText(config.text, posX, posY, glowPaint)

                textPaint.color = Color.WHITE
                canvas.drawText(config.text, posX, posY, textPaint)
            }
            TextStyleModel.OUTLINE_STROKE -> {
                val outlinePaint = Paint(textPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = textPaint.textSize * 0.16f
                    color = config.accentColor
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawText(config.text, posX, posY, outlinePaint)

                textPaint.color = config.textColor
                textPaint.style = Paint.Style.FILL
                canvas.drawText(config.text, posX, posY, textPaint)
            }
            TextStyleModel.BADGE_PILL -> {
                val paddingX = textPaint.textSize * 0.4f
                val paddingY = textPaint.textSize * 0.25f

                val rectF = RectF(
                    posX - (bounds.width() / 2f) - paddingX,
                    posY + bounds.top - paddingY,
                    posX + (bounds.width() / 2f) + paddingX,
                    posY + bounds.bottom + paddingY
                )

                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = config.accentColor
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(rectF, textPaint.textSize * 0.3f, textPaint.textSize * 0.3f, badgePaint)

                textPaint.color = config.textColor
                canvas.drawText(config.text, posX, posY, textPaint)
            }
        }

        canvas.restore()
        return result
    }
}
