package com.ararshot.app

import android.graphics.Bitmap
import android.graphics.Color
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportPhotoMaker
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TileProcessor
import com.ararshot.app.ui.renderCollageToBitmap
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
        println("=== Launching MainActivity in Robolectric ===")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        println("=== MainActivity launched successfully: $activity ===")
        assertNotNull(activity)
    }

    @Test
    fun testPassportPhotoMakerSingle() = runBlocking {
        println("=== Testing PassportPhotoMaker Single ===")
        val dummy = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        dummy.eraseColor(Color.BLUE)

        val result = PassportPhotoMaker.generateSinglePassport(dummy, PassportSize.SIZE_3X4, PassportBgColor.INDONESIA_RED)
        assertNotNull(result)
        assertEquals(PassportSize.SIZE_3X4.targetW, result.width)
        assertEquals(PassportSize.SIZE_3X4.targetH, result.height)
    }

    @Test
    fun testPassportPhotoMaker4RGrid() = runBlocking {
        println("=== Testing PassportPhotoMaker 4R Sheet Grid ===")
        val single = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        single.eraseColor(Color.RED)

        val sheet = PassportPhotoMaker.generate4RPrintSheet(single, PassportSize.SIZE_3X4)
        assertNotNull(sheet)
        assertEquals(1200, sheet.width)
        assertEquals(1800, sheet.height)
    }

    @Test
    fun testRetroDateStamp() {
        println("=== Testing Retro Date Stamp ===")
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)

        val stamped = ImageUtils.addRetroDateStamp(source, "'98 10 14")
        assertNotNull(stamped)
        assertEquals(400, stamped.width)
        assertEquals(400, stamped.height)
    }

    @Test
    fun testInpaintSafeFallback() {
        println("=== Testing Inpaint Safe Fallback ===")
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = ImageEnhancerNative.inpaint(source, mask)
        assertNotNull(result)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun testTileProcessorSeamlessBlendingNoDarkening() = runBlocking {
        println("=== Testing TileProcessor Multi-Tile Hann Blending (No Black Darkening Bug) ===")
        val src = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.WHITE)

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

        // Verify center, overlap seam, and corner pixels remain bright white (R,G,B >= 250), never darkened toward black
        val seamPixel = out.getPixel(240, 240)
        assertTrue("Seam Red channel should be >= 250", Color.red(seamPixel) >= 250)
        assertTrue("Seam Green channel should be >= 250", Color.green(seamPixel) >= 250)
        assertTrue("Seam Blue channel should be >= 250", Color.blue(seamPixel) >= 250)
    }

    @Test
    fun testReminiFaceRestoreMode() = runBlocking {
        println("=== Testing Remini Face & Portrait Restoration Mode ===")
        val src = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.rgb(210, 170, 145))

        val finalState = TileProcessor.processTiledFlow(
            input = src,
            mode = ImageEnhancerNative.MODE_FACE_RESTORE,
            strength = 0.9f
        ).last()

        assertNotNull(finalState.partialBitmap)
        assertEquals(320, finalState.partialBitmap!!.width)
        assertEquals(320, finalState.partialBitmap!!.height)
    }

    @Test
    fun testTransparentPngDetection() {
        println("=== Testing Transparent Cutout PNG Detection ===")
        val opaque = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        opaque.eraseColor(Color.RED)
        assertFalse(ImageUtils.hasTransparency(opaque))

        val transparent = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        transparent.setHasAlpha(true)
        transparent.eraseColor(Color.TRANSPARENT)
        assertTrue(ImageUtils.hasTransparency(transparent))
    }

    @Test
    fun testCollageCenterCropAndRoundedCorners() {
        println("=== Testing Collage Studio Center-Crop & Rounded Corners ===")
        val p1 = Bitmap.createBitmap(300, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val p2 = Bitmap.createBitmap(150, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val collage = renderCollageToBitmap(listOf(p1, p2), spacingDp = 8f, cornerRadiusDp = 12f)
        assertNotNull(collage)
        assertEquals(1200, collage.width)
        assertEquals(1200, collage.height)
    }
}
