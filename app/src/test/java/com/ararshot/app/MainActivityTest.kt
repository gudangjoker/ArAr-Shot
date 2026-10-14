package com.ararshot.app

import android.graphics.Bitmap
import android.graphics.Color
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportPhotoMaker
import com.ararshot.app.engine.PassportSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
