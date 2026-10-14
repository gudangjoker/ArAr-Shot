package com.ararshot.app.engine

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.min

object TileProcessor {
    private const val TAG = "TileProcessor"
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
     * blends overlapping regions seamlessly with Hann/cosine window, and emits progress flows.
     */
    fun processTiledFlow(
        input: Bitmap,
        mode: Int = ImageEnhancerNative.MODE_SUPER_RESOLUTION,
        strength: Float = 0.8f,
        tileSize: Int = DEFAULT_TILE_SIZE
    ): Flow<TileProgress> = flow {
        val width = input.width
        val height = input.height

        // If image is small enough, process directly as single tile
        if (width <= tileSize && height <= tileSize) {
            emit(TileProgress(0, 1, 0.1f))
            val result = ImageEnhancerNative.enhanceImage(input, mode, strength)
            emit(TileProgress(1, 1, 1.0f, result))
            return@flow
        }

        val isSuperRes = (mode == ImageEnhancerNative.MODE_SUPER_RESOLUTION || mode == ImageEnhancerNative.MODE_COMBINED_ENHANCE)
        val scale = if (isSuperRes) 4 else 1
        val dstW = width * scale
        val dstH = height * scale

        val targetPixels = IntArray(dstW * dstH)
        val step = tileSize - (OVERLAP * 2)

        val tilesX = ((width - (OVERLAP * 2) + step - 1) / step).coerceAtLeast(1)
        val tilesY = ((height - (OVERLAP * 2) + step - 1) / step).coerceAtLeast(1)
        val totalTiles = tilesX * tilesY
        var processedTiles = 0

        emit(TileProgress(0, totalTiles, 0.05f))

        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        for (ty in 0 until tilesY) {
            val srcY = (ty * step).coerceAtMost(height - tileSize).coerceAtLeast(0)
            val currentTileH = min(tileSize, height - srcY)

            for (tx in 0 until tilesX) {
                val srcX = (tx * step).coerceAtMost(width - tileSize).coerceAtLeast(0)
                val currentTileW = min(tileSize, width - srcX)

                // Extract tile pixels
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

                // Process tile
                val enhancedTilePixels = ImageEnhancerNative.processTileNative(
                    tilePixels,
                    currentTileW,
                    currentTileH,
                    mode,
                    strength
                )

                val outTileW = currentTileW * scale
                val outTileH = currentTileH * scale
                val dstX = srcX * scale
                val dstY = srcY * scale

                if (enhancedTilePixels != null && enhancedTilePixels.size == outTileW * outTileH) {
                    ImageEnhancerNative.blendTileNative(
                        enhancedTilePixels,
                        outTileW,
                        outTileH,
                        dstX,
                        dstY,
                        targetPixels,
                        dstW,
                        dstH
                    )
                }

                processedTiles++
                val progress = processedTiles.toFloat() / totalTiles
                emit(TileProgress(processedTiles, totalTiles, progress))
            }
        }

        val outBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(targetPixels, 0, dstW, 0, 0, dstW, dstH)
        emit(TileProgress(totalTiles, totalTiles, 1.0f, outBitmap))
    }.flowOn(Dispatchers.Default)
}
