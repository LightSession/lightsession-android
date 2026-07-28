package com.lightsession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the platform still throws what the fallback is looking for.
 *
 * The detection in `ScreenDrawing` matches on an exception message, because the platform
 * throws a plain `IllegalArgumentException` with nothing else to distinguish it. A string
 * match against a message copied out of a logcat is a guess until something asks the
 * platform directly — and if a future Android reworded it, capture on every screen with
 * images would break again with no test failing.
 *
 * So this provokes the real thing: a real hardware bitmap, drawn onto a real software
 * canvas, on whatever device is running the suite.
 */
@RunWith(AndroidJUnit4::class)
class HardwareBitmapRealityTest {

    private fun hardwareBitmap(): Bitmap? {
        val software = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(software).drawColor(Color.MAGENTA)
        // Null when the device cannot produce one — an emulator without GPU, for
        // instance. Nothing to assert there, so the test skips rather than fails.
        return software.copy(Bitmap.Config.HARDWARE, false)
    }

    @Test
    fun the_platform_message_is_the_one_the_fallback_matches() {
        val hardware = hardwareBitmap()
        assumeNotNull("this device cannot allocate a hardware bitmap", hardware)

        val target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)

        val thrown = runCatching { canvas.drawBitmap(hardware!!, 0f, 0f, null) }.exceptionOrNull()
        assertTrue(
            "drawing a hardware bitmap onto a software canvas no longer throws — " +
                "the fallback in ScreenDrawing is now dead code",
            thrown != null
        )
        assertTrue(
            "the platform threw ${thrown!!::class.java.simpleName}: ${thrown.message} — " +
                "ScreenDrawing.isHardwareBitmapFailure will not recognise it",
            thrown is IllegalArgumentException &&
                thrown.message.orEmpty().contains("hardware bitmap", ignoreCase = true)
        )
    }

    @Test
    fun pixelcopy_exists_on_this_api_level() {
        // The fallback's whole premise. minSdk is 26 and the Window overload landed in
        // 26, so this should never fail — it is here so that a minSdk change surfaces
        // as a test failure rather than as a NoSuchMethodError in the field.
        val method = android.view.PixelCopy::class.java.methods.firstOrNull {
            it.name == "request" && it.parameterTypes.firstOrNull() == android.view.Window::class.java
        }
        assertTrue("PixelCopy.request(Window, ...) is not available", method != null)
    }
}
