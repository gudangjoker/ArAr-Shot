package com.ararshot.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportPhotoMaker
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TileProcessor
import com.ararshot.app.model.ColorAdjustments
import com.ararshot.app.model.FilterPreset
import com.ararshot.app.ui.renderCollageToBitmap
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
        // Pure black stays pure black under physical linear exposure (no washed-out grey!)
        val blackOut = boosted.getPixel(0, 0)
        assertEquals(0, Color.red(blackOut))
        assertEquals(0, Color.green(blackOut))
        assertEquals(0, Color.blue(blackOut))

        // Midtone gray brightens smoothly
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
    fun testCollageCenterCropAndRoundedCorners() {
        val p1 = Bitmap.createBitmap(300, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val p2 = Bitmap.createBitmap(150, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val collage = renderCollageToBitmap(listOf(p1, p2), spacingDp = 8f, cornerRadiusDp = 12f)
        assertEquals(1200, collage.width)
        assertEquals(1200, collage.height)
    }
}
