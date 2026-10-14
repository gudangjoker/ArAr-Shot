package com.ararshot.app

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
        assert(activity != null)
    }
}
