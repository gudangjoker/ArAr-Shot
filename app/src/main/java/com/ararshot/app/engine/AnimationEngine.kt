package com.ararshot.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.ararshot.app.model.AnimationEngineType
import com.ararshot.app.model.AnimationStyle
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified TFLite inference engine for all 7 non-anime animation styles:
 * 1. Alibaba DAMO Academy DCT-Net (SIGGRAPH 2022): 2-stage full-scene + 5-point aligned head stylizer
 *    with reflect-padded aspect preservation (zero squashing) and pure Android Canvas/Matrix warping.
 * 2. White-box Cartoonization (CVPR 2020): 512x512 dynamic-range quantized scene cartoonizer.
 */
object AnimationEngine {
    private const val TAG = "ArArAnimEngine"
    private const val DCT_HEAD_SIZE = 288

    // Official DCT-Net 5-point canonical face coordinates inside 288x288 head canvas:
    // left eye, right eye, nose tip, left mouth corner, right mouth corner
    internal val DCT_REF_PTS = arrayOf(
        floatArrayOf(111.78243f, 137.62857f),
        floatArrayOf(179.73990f, 137.25270f),
        floatArrayOf(145.97716f, 176.27773f),
        floatArrayOf(118.05936f, 216.06203f),
        floatArrayOf(174.33623f, 215.75076f)
    )

    private var cachedStyleId: String? = null
    private var cachedBgInterpreter: Interpreter? = null
    private var cachedHeadInterpreter: Interpreter? = null
    private var cachedGpuDelegates = mutableListOf<GpuDelegate>()
    private var cachedHeadAlphaMask: Bitmap? = null

    @Synchronized
    fun evictStyle(style: AnimationStyle) {
        if (cachedStyleId == style.id) {
            releaseAll()
        }
    }

    @Synchronized
    fun releaseAll() {
        try { cachedBgInterpreter?.close() } catch (_: Exception) {}
        try { cachedHeadInterpreter?.close() } catch (_: Exception) {}
        cachedGpuDelegates.forEach { d -> try { d.close() } catch (_: Exception) {} }
        cachedBgInterpreter = null
        cachedHeadInterpreter = null
        cachedGpuDelegates.clear()
        cachedStyleId = null
    }

    /**
     * Stylizes [source] with the chosen [style] (must already be downloaded via [AnimationModelManager])
     * and blends with the original photo according to [strength] in [0.1f..1.0f].
     */
    @Synchronized
    fun stylize(
        context: Context,
        source: Bitmap,
        style: AnimationStyle,
        strength: Float = 0.92f
    ): Bitmap {
        require(AnimationModelManager.isInstalled(context, style)) {
            "Model ${style.displayName} belum diunduh"
        }

        val styled = when (style.engineType) {
            AnimationEngineType.DCT_NET -> runDctNet(context, source, style)
            AnimationEngineType.WHITEBOX_CARTOON -> runWhiteboxCartoon(context, source, style)
        }

        val mix = strength.coerceIn(0f, 1f)
        return if (mix >= 0.99f) styled else blendBitmaps(source, styled, mix)
    }

    private fun runDctNet(
        context: Context,
        source: Bitmap,
        style: AnimationStyle
    ): Bitmap {
        ensureDctInterpretersLoaded(context, style)
        val bgInterp = cachedBgInterpreter ?: error("Gagal memuat model ${style.displayName}")
        val headInterp = cachedHeadInterpreter

        val bgShape = bgInterp.getInputTensor(0).shape() // [1, 720, 720, 3]
        val bgH = if (bgShape.size >= 3 && bgShape[1] > 0) bgShape[1] else 720
        val bgW = if (bgShape.size >= 3 && bgShape[2] > 0) bgShape[2] else 720

        // Stage 1: Full-scene animation with aspect-ratio preserving edge-replicated padding
        val padded = createLetterboxedPaddedBitmap(source, bgW, bgH)
        val rawBgOut = runTfliteBgr(
            interpreter = bgInterp,
            inputBitmap = padded.bitmap,
            normalizeToNegOnePosOne = false // DCT-Net TFLite takes BGR [0..255] float directly
        )
        padded.bitmap.recycle()

        val croppedBg = Bitmap.createBitmap(
            rawBgOut,
            padded.padLeft,
            padded.padTop,
            padded.activeW,
            padded.activeH
        )
        rawBgOut.recycle()

        val fullStyled = Bitmap.createScaledBitmap(croppedBg, source.width, source.height, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        croppedBg.recycle()

        // Stage 2: Dedicated 5-point aligned head stylization using existing native SCRFD face detector
        if (headInterp != null) {
            val faces = try {
                ImageEnhancerNative.initEngine(context)
                ImageEnhancerNative.detectFaces(source).filter { it.score >= 0.45f && it.landmarks.size >= 10 }
            } catch (t: Throwable) {
                Log.w(TAG, "Face detection skipped for DCT-Net stage 2: ${t.message}")
                emptyList()
            }

            if (faces.isNotEmpty()) {
                val headAlpha = loadOrBuildHeadAlphaMask(context)
                val headShape = headInterp.getInputTensor(0).shape()
                val hSize = if (headShape.size >= 3 && headShape[1] > 0) headShape[1] else DCT_HEAD_SIZE

                for (face in faces.take(6)) {
                    val srcPts = Array(5) { k ->
                        floatArrayOf(face.landmarks[k * 2], face.landmarks[k * 2 + 1])
                    }
                    val scaleRef = hSize / DCT_HEAD_SIZE.toFloat()
                    val refPts = Array(5) { k ->
                        floatArrayOf(DCT_REF_PTS[k][0] * scaleRef, DCT_REF_PTS[k][1] * scaleRef)
                    }
                    val fwdVals = computeSimilarityMatrixValues(srcPts, refPts) ?: continue

                    val fwdMatrix = Matrix().apply { setValues(fwdVals) }
                    val invMatrix = Matrix()
                    if (!fwdMatrix.invert(invMatrix)) continue

                    // Warp source face cleanly onto canonical 288x288 canvas
                    val headCrop = Bitmap.createBitmap(hSize, hSize, Bitmap.Config.ARGB_8888)
                    val cropCanvas = Canvas(headCrop)
                    cropCanvas.drawColor(android.graphics.Color.WHITE)
                    val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    cropCanvas.drawBitmap(source, fwdMatrix, filterPaint)

                    // Run DCT-Net head generator
                    val headStyled = runTfliteBgr(
                        interpreter = headInterp,
                        inputBitmap = headCrop,
                        normalizeToNegOnePosOne = false
                    )
                    headCrop.recycle()

                    // Composite headStyled back onto fullStyled using inverse warp + feathered alpha mask
                    compositeWarpedHead(
                        targetFull = fullStyled,
                        headStyled = headStyled,
                        headAlphaMask = headAlpha,
                        invMatrix = invMatrix
                    )
                    headStyled.recycle()
                }
            }
        }

        return fullStyled
    }

    private fun runWhiteboxCartoon(
        context: Context,
        source: Bitmap,
        style: AnimationStyle
    ): Bitmap {
        ensureSingleInterpreterLoaded(context, style)
        val interp = cachedBgInterpreter ?: error("Gagal memuat model ${style.displayName}")

        val shape = interp.getInputTensor(0).shape() // [1, 512, 512, 3]
        val inH = if (shape.size >= 3 && shape[1] > 0) shape[1] else 512
        val inW = if (shape.size >= 3 && shape[2] > 0) shape[2] else 512

        val padded = createLetterboxedPaddedBitmap(source, inW, inH)
        val rawOut = runTfliteBgr(
            interpreter = interp,
            inputBitmap = padded.bitmap,
            normalizeToNegOnePosOne = true // White-box TFLite takes BGR [-1..1] float
        )
        padded.bitmap.recycle()

        val cropped = Bitmap.createBitmap(
            rawOut,
            padded.padLeft,
            padded.padTop,
            padded.activeW,
            padded.activeH
        )
        rawOut.recycle()

        val result = Bitmap.createScaledBitmap(cropped, source.width, source.height, true)
        cropped.recycle()
        return result
    }

    private fun ensureDctInterpretersLoaded(context: Context, style: AnimationStyle) {
        if (cachedStyleId == style.id && cachedBgInterpreter != null) return
        releaseAll()
        val bgFile = AnimationModelManager.modelFile(context, style, style.files[0].fileName)
        val headFile = AnimationModelManager.modelFile(context, style, style.files[1].fileName)
        cachedBgInterpreter = createInterpreter(bgFile)
        cachedHeadInterpreter = createInterpreter(headFile)
        cachedStyleId = style.id
    }

    private fun ensureSingleInterpreterLoaded(context: Context, style: AnimationStyle) {
        if (cachedStyleId == style.id && cachedBgInterpreter != null) return
        releaseAll()
        val modelFile = AnimationModelManager.modelFile(context, style, style.files[0].fileName)
        cachedBgInterpreter = createInterpreter(modelFile)
        cachedStyleId = style.id
    }

    private fun createInterpreter(file: File): Interpreter {
        val mapped = mapFileReadOnly(file)
        // Try GPU delegate first when supported; fall back transparently to XNNPACK CPU
        try {
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                val gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                try {
                    val opts = Interpreter.Options().apply {
                        addDelegate(gpuDelegate)
                        setNumThreads(4)
                    }
                    val interp = Interpreter(mapped, opts)
                    cachedGpuDelegates.add(gpuDelegate)
                    return interp
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate failed for ${file.name}, falling back to CPU XNNPACK: ${t.message}")
                    try { gpuDelegate.close() } catch (_: Exception) {}
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU compatibility probe skipped: ${t.message}")
        }

        val cpuOpts = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        return Interpreter(mapped, cpuOpts)
    }

    private fun mapFileReadOnly(file: File): MappedByteBuffer {
        FileInputStream(file).use { fis ->
            val channel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    /**
     * Runs a `[1, H, W, 3]` BGR float32 TFLite image-to-image model using direct ByteBuffers
     * (fast, low-GC compared to nested float[][][][] arrays).
     */
    private fun runTfliteBgr(
        interpreter: Interpreter,
        inputBitmap: Bitmap,
        normalizeToNegOnePosOne: Boolean
    ): Bitmap {
        val w = inputBitmap.width
        val h = inputBitmap.height
        val count = w * h
        val pixels = IntArray(count)
        inputBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val inputBuf = ByteBuffer.allocateDirect(count * 3 * 4).order(ByteOrder.nativeOrder())
        val inFloat = inputBuf.asFloatBuffer()

        if (normalizeToNegOnePosOne) {
            val inv1275 = 1f / 127.5f
            for (i in 0 until count) {
                val p = pixels[i]
                val r = ((p ushr 16) and 0xFF) * inv1275 - 1f
                val g = ((p ushr 8) and 0xFF) * inv1275 - 1f
                val b = (p and 0xFF) * inv1275 - 1f
                inFloat.put(b)
                inFloat.put(g)
                inFloat.put(r)
            }
        } else {
            for (i in 0 until count) {
                val p = pixels[i]
                inFloat.put((p and 0xFF).toFloat())
                inFloat.put(((p ushr 8) and 0xFF).toFloat())
                inFloat.put(((p ushr 16) and 0xFF).toFloat())
            }
        }
        inputBuf.rewind()

        val outShape = interpreter.getOutputTensor(0).shape()
        val outH = if (outShape.size >= 3 && outShape[1] > 0) outShape[1] else h
        val outW = if (outShape.size >= 3 && outShape[2] > 0) outShape[2] else w
        val outCount = outW * outH

        val outputBuf = ByteBuffer.allocateDirect(outCount * 3 * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuf, outputBuf)
        outputBuf.rewind()

        val outFloat = outputBuf.asFloatBuffer()
        val outPixels = IntArray(outCount)
        for (i in 0 until outCount) {
            val bVal = outFloat.get().coerceIn(-1f, 1f)
            val gVal = outFloat.get().coerceIn(-1f, 1f)
            val rVal = outFloat.get().coerceIn(-1f, 1f)
            val r = ((rVal + 1f) * 127.5f + 0.5f).toInt().coerceIn(0, 255)
            val g = ((gVal + 1f) * 127.5f + 0.5f).toInt().coerceIn(0, 255)
            val b = ((bVal + 1f) * 127.5f + 0.5f).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        }
    }

    /**
     * Composites a stylized head crop back into [targetFull] via inverse affine warp
     * with feathered alpha blending (zero OpenCV dependency).
     */
    private fun compositeWarpedHead(
        targetFull: Bitmap,
        headStyled: Bitmap,
        headAlphaMask: Bitmap,
        invMatrix: Matrix
    ) {
        val w = headStyled.width
        val h = headStyled.height
        val scaledMask = if (headAlphaMask.width != w || headAlphaMask.height != h) {
            Bitmap.createScaledBitmap(headAlphaMask, w, h, true)
        } else headAlphaMask

        val headPixels = IntArray(w * h)
        val maskPixels = IntArray(w * h)
        headStyled.getPixels(headPixels, 0, w, 0, 0, w, h)
        scaledMask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        if (scaledMask !== headAlphaMask) scaledMask.recycle()

        // Apply grayscale luminance of alpha mask directly into headStyled's alpha channel
        val headWithAlpha = IntArray(w * h)
        for (i in headPixels.indices) {
            val alpha = (maskPixels[i] ushr 16) and 0xFF
            headWithAlpha[i] = (alpha shl 24) or (headPixels[i] and 0x00FFFFFF)
        }

        val premultHead = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPixels(headWithAlpha, 0, w, 0, 0, w, h)
        }

        val canvas = Canvas(targetFull)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(premultHead, invMatrix, paint)
        premultHead.recycle()
    }

    private fun loadOrBuildHeadAlphaMask(context: Context): Bitmap {
        cachedHeadAlphaMask?.let { if (!it.isRecycled) return it }
        val loaded = try {
            context.assets.open("models/dct_head_alpha.jpg").use { ins ->
                BitmapFactory.decodeStream(ins)
            }
        } catch (_: Exception) {
            null
        }
        val mask = loaded ?: buildFallbackOvalMask(DCT_HEAD_SIZE, DCT_HEAD_SIZE)
        cachedHeadAlphaMask = mask
        return mask
    }

    private fun buildFallbackOvalMask(w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        val cx = w * 0.5f
        val cy = h * 0.52f
        val rx = w * 0.42f
        val ry = h * 0.44f
        for (y in 0 until h) {
            val dy = (y - cy) / ry
            for (x in 0 until w) {
                val dx = (x - cx) / rx
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                val alpha = when {
                    d <= 0.65f -> 255
                    d >= 1.0f -> 0
                    else -> {
                        val t = (1f - (d - 0.65f) / 0.35f)
                        val smooth = t * t * (3f - 2f * t)
                        (smooth * 255f).roundToInt().coerceIn(0, 255)
                    }
                }
                pixels[y * w + x] = (0xFF shl 24) or (alpha shl 16) or (alpha shl 8) or alpha
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    internal data class LetterboxResult(
        val bitmap: Bitmap,
        val padLeft: Int,
        val padTop: Int,
        val activeW: Int,
        val activeH: Int
    )

    /**
     * Scales [source] to fit inside [targetW] x [targetH] preserving exact aspect ratio,
     * and replicates edge pixels into the padding area so GAN convolutions never see black borders.
     */
    internal fun createLetterboxedPaddedBitmap(
        source: Bitmap,
        targetW: Int,
        targetH: Int
    ): LetterboxResult {
        val scale = min(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        val activeW = max(1, (source.width * scale).roundToInt().coerceAtMost(targetW))
        val activeH = max(1, (source.height * scale).roundToInt().coerceAtMost(targetH))
        val padLeft = (targetW - activeW) / 2
        val padTop = (targetH - activeH) / 2

        val scaled = Bitmap.createScaledBitmap(source, activeW, activeH, true)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Draw active image in the center
        val dstCenter = Rect(padLeft, padTop, padLeft + activeW, padTop + activeH)
        canvas.drawBitmap(scaled, null, dstCenter, paint)

        // Replicate edge strips into padding to avoid dark border bleeding in conv layers
        if (padTop > 0) {
            canvas.drawBitmap(scaled, Rect(0, 0, activeW, 1), Rect(padLeft, 0, padLeft + activeW, padTop), paint)
        }
        val bottomY = padTop + activeH
        if (bottomY < targetH) {
            canvas.drawBitmap(scaled, Rect(0, activeH - 1, activeW, activeH), Rect(padLeft, bottomY, padLeft + activeW, targetH), paint)
        }
        if (padLeft > 0) {
            canvas.drawBitmap(out, Rect(padLeft, 0, padLeft + 1, targetH), Rect(0, 0, padLeft, targetH), paint)
        }
        val rightX = padLeft + activeW
        if (rightX < targetW) {
            canvas.drawBitmap(out, Rect(rightX - 1, 0, rightX, targetH), Rect(rightX, 0, targetW, targetH), paint)
        }

        scaled.recycle()
        return LetterboxResult(out, padLeft, padTop, activeW, activeH)
    }

    /**
     * Exact closed-form 2D non-reflective similarity transform (Umeyama) mapping [srcPts] -> [dstPts].
     * Returns 9-element row-major Android [Matrix] values: [a, -b, tx, b, a, ty, 0, 0, 1].
     */
    internal fun computeSimilarityMatrixValues(
        srcPts: Array<FloatArray>,
        dstPts: Array<FloatArray>
    ): FloatArray? {
        val n = min(srcPts.size, dstPts.size)
        if (n < 2) return null

        var meanSx = 0.0
        var meanSy = 0.0
        var meanDx = 0.0
        var meanDy = 0.0
        for (i in 0 until n) {
            meanSx += srcPts[i][0]
            meanSy += srcPts[i][1]
            meanDx += dstPts[i][0]
            meanDy += dstPts[i][1]
        }
        meanSx /= n
        meanSy /= n
        meanDx /= n
        meanDy /= n

        var denom = 0.0
        var numA = 0.0
        var numB = 0.0
        for (i in 0 until n) {
            val sx = srcPts[i][0] - meanSx
            val sy = srcPts[i][1] - meanSy
            val dx = dstPts[i][0] - meanDx
            val dy = dstPts[i][1] - meanDy
            denom += sx * sx + sy * sy
            numA += sx * dx + sy * dy
            numB += sx * dy - sy * dx
        }
        if (denom < 1e-6) return null

        val a = (numA / denom).toFloat()
        val b = (numB / denom).toFloat()
        if (abs(a) < 1e-6f && abs(b) < 1e-6f) return null

        val tx = (meanDx - (a * meanSx - b * meanSy)).toFloat()
        val ty = (meanDy - (b * meanSx + a * meanSy)).toFloat()

        return floatArrayOf(
            a, -b, tx,
            b,  a, ty,
            0f, 0f, 1f
        )
    }

    internal fun blendBitmaps(original: Bitmap, stylized: Bitmap, mix: Float): Bitmap {
        val w = original.width
        val h = original.height
        val styledMatch = if (stylized.width != w || stylized.height != h) {
            Bitmap.createScaledBitmap(stylized, w, h, true)
        } else stylized

        val origPx = IntArray(w * h)
        val styPx = IntArray(w * h)
        original.getPixels(origPx, 0, w, 0, 0, w, h)
        styledMatch.getPixels(styPx, 0, w, 0, 0, w, h)
        if (styledMatch !== stylized) styledMatch.recycle()

        val inv = 1f - mix
        val out = IntArray(w * h)
        for (i in origPx.indices) {
            val o = origPx[i]
            val s = styPx[i]
            val a = (o ushr 24) and 0xFF
            val r = (((o ushr 16) and 0xFF) * inv + ((s ushr 16) and 0xFF) * mix + 0.5f).toInt().coerceIn(0, 255)
            val g = (((o ushr 8) and 0xFF) * inv + ((s ushr 8) and 0xFF) * mix + 0.5f).toInt().coerceIn(0, 255)
            val b = ((o and 0xFF) * inv + (s and 0xFF) * mix + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(original.hasAlpha())
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }
}
