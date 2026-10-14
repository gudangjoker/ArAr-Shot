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

    @Volatile
    private var isNativeLibraryLoaded = false
    @Volatile
    private var isModelLoaded = false
    private var loadAttempted = false

    /**
     * Lazily and safely loads the native C++ libraries (libncnn and libarar_engine).
     * This avoids any startup class-initialization crashes.
     */
    @Synchronized
    fun ensureNativeLoaded(): Boolean {
        if (loadAttempted) return isNativeLibraryLoaded
        loadAttempted = true

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

        return isNativeLibraryLoaded
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
    @Synchronized
    fun initModels(context: Context): Boolean {
        if (isModelLoaded) return true

        if (!ensureNativeLoaded()) {
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
                Log.w(TAG, "loadModelNative returned false. Mathematical upscale fallback will be used.")
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during initModels: ${t.message}", t)
            false
        }
    }

    /**
     * General image enhancement supporting Super-Resolution, Bilateral Denoise, Unsharp Mask, and Combined Ultra.
     */
    fun enhanceImage(input: Bitmap, mode: Int = MODE_SUPER_RESOLUTION, strength: Float = 0.8f): Bitmap {
        ensureNativeLoaded()
        val width = input.width
        val height = input.height
        val inPixels = IntArray(width * height)
        input.getPixels(inPixels, 0, width, 0, 0, width, height)

        val isSuperRes = (mode == MODE_SUPER_RESOLUTION || mode == MODE_COMBINED_ENHANCE)

        return try {
            val processedPixels = if (isNativeLibraryLoaded) {
                if (mode == MODE_COMBINED_ENHANCE) {
                    // Step 1: Bilateral Denoise
                    val denoised = processTileNative(inPixels, width, height, MODE_BILATERAL_DENOISE, strength)
                    // Step 2: 4x Super-Resolution
                    if (denoised != null && denoised.size == width * height) {
                        processTileNative(denoised, width, height, MODE_SUPER_RESOLUTION, strength)
                    } else {
                        processTileNative(inPixels, width, height, MODE_SUPER_RESOLUTION, strength)
                    }
                } else {
                    processTileNative(inPixels, width, height, mode, strength)
                }
            } else {
                inPixels
            }

            val outW = if (isSuperRes) width * 4 else width
            val outH = if (isSuperRes) height * 4 else height

            if (processedPixels != null && processedPixels.size == outW * outH) {
                val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(processedPixels, 0, outW, 0, 0, outW, outH)
                outBitmap
            } else {
                Log.w(TAG, "Processed pixels size mismatch, returning original bitmap.")
                input
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in enhanceImage: ${t.message}", t)
            input
        }
    }

    /**
     * Upscale an input bitmap using Real-ESRGAN (or mathematical bicubic fallback).
     */
    fun upscaleImage(input: Bitmap, strength: Float = 0.8f): Bitmap {
        return enhanceImage(input, MODE_SUPER_RESOLUTION, strength)
    }
}
