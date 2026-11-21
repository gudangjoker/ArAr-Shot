package com.ararshot.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.ararshot.app.engine.AnimationEngine
import com.ararshot.app.engine.CollageRenderer
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportPhotoMaker
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TileProcessor
import com.ararshot.app.model.AnimationStyle
import com.ararshot.app.model.CollageAspectRatio
import com.ararshot.app.model.CollageBgColor
import com.ararshot.app.model.CollageCellState
import com.ararshot.app.model.CollageLayouts
import com.ararshot.app.model.ColorAdjustments
import com.ararshot.app.model.FilterPreset
import com.ararshot.app.model.MAX_COLLAGE_PHOTOS
import com.ararshot.app.viewmodel.EnhancerViewModel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun testMainActivityLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
    }

    @Test
    fun testLinearColorExposurePreservesPureBlackAndRaisesMidtones() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(100, 100, 100))
        bmp.setPixel(0, 0, Color.BLACK)

        val boosted = ImageEnhancerNative.applyLinearColor(bmp, ColorAdjustments(exposure = 1.0f))
        val blackOut = boosted.getPixel(0, 0)
        assertEquals(0, Color.red(blackOut))
        assertEquals(0, Color.green(blackOut))
        assertEquals(0, Color.blue(blackOut))

        val midOut = boosted.getPixel(8, 8)
        assertTrue("Midtone should brighten under +1 EV", Color.red(midOut) > 130)
    }

    @Test
    fun testLinearColorWhiteBalancePreservesBlack() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(128, 128, 128))
        bmp.setPixel(0, 0, Color.BLACK)

        val warm = ImageEnhancerNative.applyLinearColor(bmp, ColorAdjustments(temp = 0.8f))
        assertEquals(0, Color.red(warm.getPixel(0, 0)))
        val mid = warm.getPixel(4, 4)
        assertTrue("Warm temp should push red above blue", Color.red(mid) > Color.blue(mid))
    }

    @Test
    fun testColorAdjustmentsPackingAndHsl() {
        val adj = ColorAdjustments(
            exposure = 0.5f,
            contrast = 0.2f,
            preset = FilterPreset.FILM_WARM,
            presetStrength = 0.75f
        ).withHsl(bandIndex = 5, hue = 0.25f, sat = 0.4f, lum = -0.1f)

        assertFalse(adj.isIdentity())
        val packed = adj.toFloatArray()
        assertEquals(ColorAdjustments.PARAMS_COUNT, packed.size)
        assertEquals(0.5f, packed[0], 1e-5f)
        assertEquals(FilterPreset.FILM_WARM.id.toFloat(), packed[16], 1e-5f)
        assertEquals(0.25f, packed[18 + 5], 1e-5f)
        assertEquals(0.4f, packed[26 + 5], 1e-5f)
        assertEquals(-0.1f, packed[34 + 5], 1e-5f)
    }

    @Test
    fun testNonDestructiveUndoRedoInViewModel() {
        val vm = EnhancerViewModel()
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        vm.setSourceBitmap(src)

        assertFalse(vm.canUndo.value)
        vm.updateAdjustments({ it.copy(exposure = 0.6f) }, recordUndoImmediately = true)
        assertTrue(vm.canUndo.value)
        assertEquals(0.6f, vm.adjustments.value.exposure, 1e-4f)

        vm.undo()
        assertEquals(0f, vm.adjustments.value.exposure, 1e-4f)
        assertTrue(vm.canRedo.value)

        vm.redo()
        assertEquals(0.6f, vm.adjustments.value.exposure, 1e-4f)
    }

    @Test
    fun testCropAndStraightenDimensions() {
        val src = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val cropped = ImageUtils.cropAndStraighten(src, RectF(0.25f, 0f, 0.75f, 1f), straightenDegrees = 5f)
        assertEquals(200, cropped.width)
        assertEquals(200, cropped.height)
    }

    @Test
    fun testPassportPhotoMakerSingleAnd4R() = runBlocking {
        val dummy = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val single = PassportPhotoMaker.generateSinglePassport(dummy, PassportSize.SIZE_3X4, PassportBgColor.INDONESIA_RED)
        assertEquals(PassportSize.SIZE_3X4.targetW, single.width)
        assertEquals(PassportSize.SIZE_3X4.targetH, single.height)

        val sheet = PassportPhotoMaker.generate4RPrintSheet(single, PassportSize.SIZE_3X4)
        assertEquals(1200, sheet.width)
        assertEquals(1800, sheet.height)
    }

    @Test
    fun testTileProcessorSeamlessBlendingNoDarkening() = runBlocking {
        val src = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val finalState = TileProcessor.processTiledFlow(
            input = src,
            mode = ImageEnhancerNative.MODE_UNSHARP_MASK,
            strength = 0.8f,
            tileSize = 256
        ).last()

        val out = finalState.partialBitmap
        assertNotNull(out)
        assertEquals(480, out!!.width)
        assertEquals(480, out.height)
        val seamPixel = out.getPixel(240, 240)
        assertTrue(Color.red(seamPixel) >= 250)
        assertTrue(Color.green(seamPixel) >= 250)
        assertTrue(Color.blue(seamPixel) >= 250)
    }

    @Test
    fun testTransparentPngDetection() {
        val opaque = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        assertFalse(ImageUtils.hasTransparency(opaque))

        val transparent = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            eraseColor(Color.TRANSPARENT)
        }
        assertTrue(ImageUtils.hasTransparency(transparent))
    }

    @Test
    fun testFlexibleCollageUpToNinePhotosAndSwap() {
        val vm = EnhancerViewModel()
        val bitmaps = List(11) { idx ->
            Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(20 * idx, 80, 200 - 15 * idx))
            }
        }
        // Capped cleanly at MAX_COLLAGE_PHOTOS (9)
        vm.addCollagePhotos(bitmaps)
        assertEquals(MAX_COLLAGE_PHOTOS, vm.collageCells.value.size)

        val firstBefore = vm.collageCells.value[0].id
        val lastBefore = vm.collageCells.value[8].id
        vm.swapCollageCells(0, 8)
        assertEquals(lastBefore, vm.collageCells.value[0].id)
        assertEquals(firstBefore, vm.collageCells.value[8].id)

        // Render 9-photo 4:5 IG collage with custom per-cell pan, zoom, and rotation
        val template9 = CollageLayouts.templatesFor(9).first()
        val cells = vm.collageCells.value.mapIndexed { idx, c ->
            c.copy(scale = 1.5f, panX = if (idx % 2 == 0) 0.5f else -0.5f, panY = 0.25f, rotationDegrees = (idx % 4) * 90)
        }
        val rendered = CollageRenderer.renderCollageToBitmap(
            cells = cells,
            template = template9,
            aspectRatio = CollageAspectRatio.IG_FEED,
            spacingDp = 8f,
            outerPaddingDp = 10f,
            cornerRadiusDp = 12f,
            bgColorArgb = CollageBgColor.WHITE.argb,
            longEdgePx = 1000
        )
        assertEquals(800, rendered.width)
        assertEquals(1000, rendered.height)
    }

    @Test
    fun testAllCollageCountsHaveValidTemplates() {
        for (count in 1..MAX_COLLAGE_PHOTOS) {
            val templates = CollageLayouts.templatesFor(count)
            assertTrue("Expected templates for $count photos", templates.isNotEmpty())
            templates.forEach { tpl ->
                assertEquals(count, tpl.cells.size)
                val rects = CollageRenderer.computeCellPixelRects(tpl.cells, 1000f, 1000f, 20f, 10f)
                assertEquals(count, rects.size)
                rects.forEach { r ->
                    assertTrue(r.width() > 0f && r.height() > 0f)
                }
            }
        }
    }

    @Test
    fun testAnimationEngineSimilarityTransformAndLetterbox() {
        // Verify exact 2D Umeyama similarity transform: scale 2x + 90 deg CCW + translate (30, 50)
        val srcPts = arrayOf(
            floatArrayOf(10f, 20f),
            floatArrayOf(40f, 20f),
            floatArrayOf(25f, 35f),
            floatArrayOf(15f, 50f),
            floatArrayOf(35f, 50f)
        )
        val dstPts = Array(5) { i ->
            val x = srcPts[i][0]
            val y = srcPts[i][1]
            floatArrayOf(-2f * y + 30f, 2f * x + 50f)
        }
        val m = AnimationEngine.computeSimilarityMatrixValues(srcPts, dstPts)
        assertNotNull(m)
        for (i in 0 until 5) {
            val u = m!![0] * srcPts[i][0] + m[1] * srcPts[i][1] + m[2]
            val v = m[3] * srcPts[i][0] + m[4] * srcPts[i][1] + m[5]
            assertEquals(dstPts[i][0], u, 0.01f)
            assertEquals(dstPts[i][1], v, 0.01f)
        }

        // Verify aspect-preserving letterbox padding
        val wide = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val lb = AnimationEngine.createLetterboxedPaddedBitmap(wide, 256, 256)
        assertEquals(256, lb.bitmap.width)
        assertEquals(256, lb.bitmap.height)
        assertEquals(256, lb.activeW)
        assertEquals(128, lb.activeH)
        assertEquals(64, lb.padTop)

        // Verify all 7 non-anime animation styles are defined with valid URLs
        assertEquals(7, AnimationStyle.entries.size)
        assertTrue(AnimationStyle.entries.all { it.files.isNotEmpty() && it.totalApproxBytes > 1_000_000L })
    }
}
