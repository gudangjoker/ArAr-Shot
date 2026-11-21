package com.ararshot.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ararshot.app.model.CollageAspectRatio
import com.ararshot.app.model.CollageCellState
import com.ararshot.app.model.CollageLayoutTemplate
import com.ararshot.app.model.NormalizedRect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic WYSIWYG layout math and high-resolution bitmap renderer for 1..9 photo collages.
 * Both Compose preview and gallery export share [computeCellPixelRects] and [computeCoverTransform].
 */
object CollageRenderer {

    /**
     * Converts normalized [0..1] cell definitions into exact pixel [RectF]s inside a canvas of
     * [canvasW] x [canvasH], respecting [outerPaddingPx] and inner [spacingPx].
     */
    fun computeCellPixelRects(
        cells: List<NormalizedRect>,
        canvasW: Float,
        canvasH: Float,
        outerPaddingPx: Float,
        spacingPx: Float
    ): List<RectF> {
        val innerW = (canvasW - outerPaddingPx * 2f).coerceAtLeast(1f)
        val innerH = (canvasH - outerPaddingPx * 2f).coerceAtLeast(1f)
        val halfGap = spacingPx * 0.5f

        return cells.map { nr ->
            val rawL = outerPaddingPx + nr.left * innerW
            val rawT = outerPaddingPx + nr.top * innerH
            val rawR = outerPaddingPx + nr.right * innerW
            val rawB = outerPaddingPx + nr.bottom * innerH

            val insetL = if (nr.left > 0.001f) halfGap else 0f
            val insetT = if (nr.top > 0.001f) halfGap else 0f
            val insetR = if (nr.right < 0.999f) halfGap else 0f
            val insetB = if (nr.bottom < 0.999f) halfGap else 0f

            RectF(
                rawL + insetL,
                rawT + insetT,
                (rawR - insetR).coerceAtLeast(rawL + insetL + 1f),
                (rawB - insetB).coerceAtLeast(rawT + insetT + 1f)
            )
        }
    }

    data class CellImageTransform(
        val totalScale: Float,
        val maxPanX: Float,
        val maxPanY: Float,
        val txPx: Float,
        val tyPx: Float
    )

    /**
     * Computes exact cover-fit scale + clamped translation for a cell so preview and export match 1:1.
     */
    fun computeCoverTransform(
        bitmapW: Int,
        bitmapH: Int,
        rotationDegrees: Int,
        cellW: Float,
        cellH: Float,
        userScale: Float,
        panX: Float,
        panY: Float
    ): CellImageTransform {
        val swapped = (rotationDegrees % 180) != 0
        val effW = (if (swapped) bitmapH else bitmapW).toFloat().coerceAtLeast(1f)
        val effH = (if (swapped) bitmapW else bitmapH).toFloat().coerceAtLeast(1f)

        val baseScale = max(cellW / effW, cellH / effH)
        val totalScale = baseScale * userScale.coerceIn(1f, 4f)
        val drawW = effW * totalScale
        val drawH = effH * totalScale
        val maxPanX = max(0f, (drawW - cellW) * 0.5f)
        val maxPanY = max(0f, (drawH - cellH) * 0.5f)
        val txPx = panX.coerceIn(-1f, 1f) * maxPanX
        val tyPx = panY.coerceIn(-1f, 1f) * maxPanY

        return CellImageTransform(totalScale, maxPanX, maxPanY, txPx, tyPx)
    }

    /**
     * Renders the complete collage to a high-resolution ARGB_8888 bitmap (default 1800px long edge).
     */
    fun renderCollageToBitmap(
        cells: List<CollageCellState>,
        template: CollageLayoutTemplate,
        aspectRatio: CollageAspectRatio,
        spacingDp: Float,
        outerPaddingDp: Float,
        cornerRadiusDp: Float,
        bgColorArgb: Int,
        longEdgePx: Int = 1800
    ): Bitmap {
        val outW: Int
        val outH: Int
        if (aspectRatio.ratio >= 1f) {
            outW = longEdgePx
            outH = max(1, (longEdgePx / aspectRatio.ratio).roundToInt())
        } else {
            outH = longEdgePx
            outW = max(1, (longEdgePx * aspectRatio.ratio).roundToInt())
        }

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(bgColorArgb)

        if (cells.isEmpty()) return output

        // Scale dp values proportionally to the export canvas (~360dp reference preview width)
        val scaleFromDp = minOf(outW, outH) / 360f
        val spacingPx = spacingDp * scaleFromDp
        val outerPaddingPx = outerPaddingDp * scaleFromDp
        val cornerRadiusPx = cornerRadiusDp * scaleFromDp

        val rects = computeCellPixelRects(
            cells = template.cells,
            canvasW = outW.toFloat(),
            canvasH = outH.toFloat(),
            outerPaddingPx = outerPaddingPx,
            spacingPx = spacingPx
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val count = minOf(cells.size, rects.size)
        for (i in 0 until count) {
            val cell = cells[i]
            val dstRect = rects[i]
            drawCellBitmap(canvas, cell, dstRect, cornerRadiusPx, paint)
        }

        return output
    }

    private fun drawCellBitmap(
        canvas: Canvas,
        cell: CollageCellState,
        dstRect: RectF,
        cornerRadiusPx: Float,
        paint: Paint
    ) {
        val cellW = dstRect.width()
        val cellH = dstRect.height()
        if (cellW <= 0f || cellH <= 0f) return

        val bmp = cell.bitmap
        val transform = computeCoverTransform(
            bitmapW = bmp.width,
            bitmapH = bmp.height,
            rotationDegrees = cell.rotationDegrees,
            cellW = cellW,
            cellH = cellH,
            userScale = cell.scale,
            panX = cell.panX,
            panY = cell.panY
        )

        val saveCount = canvas.save()
        if (cornerRadiusPx > 0.5f) {
            val clipPath = Path().apply {
                addRoundRect(dstRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
        } else {
            canvas.clipRect(dstRect)
        }

        val matrix = Matrix().apply {
            // Center bitmap at origin -> rotate -> scale -> translate to cell center + pan offset
            postTranslate(-bmp.width * 0.5f, -bmp.height * 0.5f)
            if (cell.rotationDegrees != 0) {
                postRotate(cell.rotationDegrees.toFloat())
            }
            postScale(transform.totalScale, transform.totalScale)
            postTranslate(
                dstRect.centerX() + transform.txPx,
                dstRect.centerY() + transform.tyPx
            )
        }

        canvas.drawBitmap(bmp, matrix, paint)
        canvas.restoreToCount(saveCount)
    }
}
