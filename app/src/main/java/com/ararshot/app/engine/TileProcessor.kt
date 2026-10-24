package com.ararshot.app.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

object TileProcessor {
    private const val DEFAULT_TILE_SIZE = 384
    private const val OVERLAP = 24

    data class TileProgress(
        val currentTile: Int,
        val totalTiles: Int,
        val progress: Float,
        val partialBitmap: Bitmap? = null
    )

    /**
     * Splits an input bitmap into overlapping tiles, processes each tile via ImageEnhancerNative,
     * blends overlapping regions seamlessly with a persistent 2D Hann/cosine weight accumulator,
     * and emits progress flows.
     */
    fun processTiledFlow(
        input: Bitmap,
        mode: Int = ImageEnhancerNative.MODE_SUPER_RESOLUTION,
        strength: Float = 0.8f,
        tileSize: Int = DEFAULT_TILE_SIZE
    ): Flow<TileProgress> = flow {
        ImageEnhancerNative.ensureNativeLoaded()

        // Remini Face & Portrait Restoration requires full-face landmark context
        if (mode == ImageEnhancerNative.MODE_FACE_RESTORE) {
            emit(TileProgress(0, 2, 0.25f))
            val faceRestored = ImageEnhancerNative.restoreFaces(input, strength)
            emit(TileProgress(2, 2, 1.0f, faceRestored))
            return@flow
        }

        val isSuperRes = (mode == ImageEnhancerNative.MODE_SUPER_RESOLUTION ||
            mode == ImageEnhancerNative.MODE_COMBINED_ENHANCE)
        val safeInput = if (isSuperRes) ImageEnhancerNative.capForSuperResolution(input, 4) else input

        val width = safeInput.width
        val height = safeInput.height

        // If image fits within a single tile, process directly
        if (width <= tileSize && height <= tileSize) {
            emit(TileProgress(0, 1, 0.15f))
            val result = ImageEnhancerNative.enhanceImage(safeInput, mode, strength)
            emit(TileProgress(1, 1, 1.0f, result))
            return@flow
        }

        val scale = if (isSuperRes) 4 else 1
        val dstW = width * scale
        val dstH = height * scale

        val targetPixels = IntArray(dstW * dstH)
        // Persistent weight accumulator initialized to 0.0f across all tiles (fixes black halo/darkening bug)
        val weightMap = FloatArray(dstW * dstH)

        val step = (tileSize - (OVERLAP * 2)).coerceAtLeast(32)
        val tilesX = ((width - (OVERLAP * 2) + step - 1) / step).coerceAtLeast(1)
        val tilesY = ((height - (OVERLAP * 2) + step - 1) / step).coerceAtLeast(1)
        val totalTiles = tilesX * tilesY
        var processedTiles = 0

        emit(TileProgress(0, totalTiles, 0.05f))

        val inPixels = IntArray(width * height)
        safeInput.getPixels(inPixels, 0, width, 0, 0, width, height)

        val overlapOutPx = OVERLAP * scale

        for (ty in 0 until tilesY) {
            val srcY = (ty * step).coerceAtMost((height - tileSize).coerceAtLeast(0))
            val currentTileH = min(tileSize, height - srcY)

            for (tx in 0 until tilesX) {
                val srcX = (tx * step).coerceAtMost((width - tileSize).coerceAtLeast(0))
                val currentTileW = min(tileSize, width - srcX)

                val tilePixels = IntArray(currentTileW * currentTileH)
                for (row in 0 until currentTileH) {
                    System.arraycopy(
                        inPixels,
                        (srcY + row) * width + srcX,
                        tilePixels,
                        row * currentTileW,
                        currentTileW
                    )
                }

                val outTileW = currentTileW * scale
                val outTileH = currentTileH * scale
                val dstX = srcX * scale
                val dstY = srcY * scale

                val enhancedTilePixels = if (ImageEnhancerNative.isNativeLoaded()) {
                    ImageEnhancerNative.processTileNative(
                        tilePixels,
                        currentTileW,
                        currentTileH,
                        mode,
                        strength
                    )
                } else {
                    // JVM fallback for unit tests
                    if (scale > 1) {
                        val tileBmp = Bitmap.createBitmap(currentTileW, currentTileH, Bitmap.Config.ARGB_8888)
                        tileBmp.setPixels(tilePixels, 0, currentTileW, 0, 0, currentTileW, currentTileH)
                        val scaledBmp = Bitmap.createScaledBitmap(tileBmp, outTileW, outTileH, true)
                        val outArr = IntArray(outTileW * outTileH)
                        scaledBmp.getPixels(outArr, 0, outTileW, 0, 0, outTileW, outTileH)
                        outArr
                    } else {
                        tilePixels
                    }
                }

                if (enhancedTilePixels != null && enhancedTilePixels.size == outTileW * outTileH) {
                    if (ImageEnhancerNative.isNativeLoaded()) {
                        ImageEnhancerNative.blendTileWithWeightNative(
                            enhancedTilePixels,
                            targetPixels,
                            weightMap,
                            outTileW,
                            outTileH,
                            dstW,
                            dstH,
                            dstX,
                            dstY,
                            overlapOutPx
                        )
                    } else {
                        blendTileJvmFallback(
                            enhancedTilePixels,
                            targetPixels,
                            weightMap,
                            outTileW,
                            outTileH,
                            dstW,
                            dstH,
                            dstX,
                            dstY,
                            overlapOutPx
                        )
                    }
                }

                processedTiles++
                val progress = processedTiles.toFloat() / totalTiles.toFloat()
                emit(TileProgress(processedTiles, totalTiles, progress))
            }
        }

        val outBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(targetPixels, 0, dstW, 0, 0, dstW, dstH)
        emit(TileProgress(totalTiles, totalTiles, 1.0f, outBitmap))
    }.flowOn(Dispatchers.Default)

    /**
     * Exact JVM mirror of C++ cosineTileBlendWithOverlap for unit test verification.
     */
    private fun blendTileJvmFallback(
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
    ) {
        val ramp = overlapPx.coerceIn(1, min(tileW, tileH) / 2)
        for (ty in 0 until tileH) {
            val gy = offsetY + ty
            if (gy < 0 || gy >= targetH) continue

            var wy = 1.0f
            if (offsetY > 0 && ty < ramp) {
                wy = 0.5f * (1.0f - cos(PI * (ty + 0.5) / ramp).toFloat())
            } else if (offsetY + tileH < targetH && ty >= tileH - ramp) {
                val d = tileH - 1 - ty
                wy = 0.5f * (1.0f - cos(PI * (d + 0.5) / ramp).toFloat())
            }

            for (tx in 0 until tileW) {
                val gx = offsetX + tx
                if (gx < 0 || gx >= targetW) continue

                var wx = 1.0f
                if (offsetX > 0 && tx < ramp) {
                    wx = 0.5f * (1.0f - cos(PI * (tx + 0.5) / ramp).toFloat())
                } else if (offsetX + tileW < targetW && tx >= tileW - ramp) {
                    val d = tileW - 1 - tx
                    wx = 0.5f * (1.0f - cos(PI * (d + 0.5) / ramp).toFloat())
                }

                val w = (wx * wy).coerceAtLeast(1e-4f)
                val targetIdx = gy * targetW + gx
                val tilePixel = tilePixels[ty * tileW + tx]
                val existingW = weightMap[targetIdx]

                if (existingW <= 1e-5f) {
                    targetPixels[targetIdx] = tilePixel
                    weightMap[targetIdx] = w
                } else {
                    val dstPixel = targetPixels[targetIdx]
                    val totalW = existingW + w
                    val invTotal = 1.0f / totalW

                    val a = (((dstPixel ushr 24) and 0xFF) * existingW + ((tilePixel ushr 24) and 0xFF) * w) * invTotal
                    val r = (((dstPixel ushr 16) and 0xFF) * existingW + ((tilePixel ushr 16) and 0xFF) * w) * invTotal
                    val g = (((dstPixel ushr 8) and 0xFF) * existingW + ((tilePixel ushr 8) and 0xFF) * w) * invTotal
                    val b = ((dstPixel and 0xFF) * existingW + (tilePixel and 0xFF) * w) * invTotal

                    targetPixels[targetIdx] = (a.toInt().coerceIn(0, 255) shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
                    weightMap[targetIdx] = totalW
                }
            }
        }
    }
}
