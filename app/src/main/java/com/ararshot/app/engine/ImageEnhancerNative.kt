package com.ararshot.app.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log

object ImageEnhancerNative {
    private const val TAG = "ImageEnhancerNative"

    const val MODE_SUPER_RESOLUTION = 0
    const val MODE_BILATERAL_DENOISE = 1
    const val MODE_UNSHARP_MASK = 2
    const val MODE_COMBINED_ENHANCE = 3

    private var isNativeLibraryLoaded = false
    private var isModelLoaded = false

    init {
        // Attempt to load libncnn first (shared dependency on Android)
        try {
            System.loadLibrary("ncnn")
            Log.i(TAG, "libncnn loaded successfully.")
        } catch (t: Throwable) {
            Log.w(TAG, "libncnn load status: ${t.message}")
        }

        // Load arar_engine native library
        try {
            System.loadLibrary("arar_engine")
            isNativeLibraryLoaded = true
            Log.i(TAG, "Native library 'arar_engine' loaded successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load 'arar_engine' native library: ${t.message}")
            isNativeLibraryLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isNativeLibraryLoaded
    fun isModelReady(): Boolean = isModelLoaded

    private external fun loadModelNative(
        assetManager: AssetManager,
        paramPath: String,
        binPath: String
    ): Boolean

    external fun processTileNative(
        inPixels: IntArray,
        width: Int,
        height: Int,
        mode: Int,
        strength: Float
    ): IntArray

    external fun processBitmapNative(
        bitmapIn: Bitmap,
        bitmapOut: Bitmap,
        mode: Int,
        strength: Float
    ): Boolean

    external fun blendTileNative(
        tilePixels: IntArray,
        tileW: Int,
        tileH: Int,
        dstX: Int,
        dstY: Int,
        targetPixels: IntArray,
        targetW: Int,
        targetH: Int
    ): Boolean

    /**
     * Initializes NCNN Vulkan compute and loads Real-ESRGAN model weights from APK assets.
     */
    fun initModels(context: Context): Boolean {
        if (!isNativeLibraryLoaded) {
            Log.w(TAG, "Native library not loaded. Image enhancement will use mathematical fallbacks.")
            return false
        }

        return try {
            val assetManager = context.assets
            val paramPath = "models/realesr-general-x4v3.param"
            val binPath = "models/realesr-general-x4v3.bin"

            Log.i(TAG, "Loading NCNN models from assets: $paramPath, $binPath")
            val success = loadModelNative(assetManager, paramPath, binPath)
            isModelLoaded = success

            if (success) {
                Log.i(TAG, "Real-ESRGAN NCNN model successfully loaded and ready for inference.")
            } else {
                Log.w(TAG, "loadModelNative returned false. Bicubic upscale fallback will be used.")
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during initModels: ${t.message}", t)
            false
        }
    }

    /**
     * Upscale an input bitmap using Real-ESRGAN (or mathematical bicubic fallback).
     */
    fun upscaleImage(input: Bitmap, strength: Float = 0.8f): Bitmap {
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        val outPixels = if (isNativeLibraryLoaded) {
            processTileNative(inPixels, width, height, MODE_SUPER_RESOLUTION, strength)
        } else {
            inPixels
        }

        val outW = width * 4
        val outH = height * 4
        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        if (outPixels.size == outW * outH) {
            outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        }
        return outBitmap
    }
}
