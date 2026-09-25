package com.lightsession.mapper

import android.content.res.Configuration
import com.lightsession.LightSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which theme a capture is filed under when an embedder draws in an appearance of its own.
 *
 * A Flutter app with `ThemeMode.dark` paints dark on a device in light mode, and the platform's night
 * mode — all the SDK read — said light: measured on an emulator, a screen dark to the pixel was filed
 * as `Light`.
 */
class AppearanceTest {

    private val day = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
    private val night = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES

    @After
    fun tearDown() = SuppliedScreen.clear()

    @Test
    fun without_an_embedder_the_platform_decides() {
        assertEquals("Light", themeName(day, null))
        assertEquals("Dark", themeName(night, null))
        assertEquals("Undefined", themeName(Configuration.UI_MODE_NIGHT_UNDEFINED, null))
    }

    @Test
    fun an_embedder_drawing_dark_on_a_light_device_is_dark() {
        assertEquals("Dark", themeName(day, true))
        assertEquals("Light", themeName(night, false))
    }

    @Test
    fun the_appearance_stands_until_it_is_withdrawn() {
        LightSession.getInstance().setAppearance(true)
        assertEquals("Dark", themeName(day, SuppliedScreen.dark))

        LightSession.getInstance().setAppearance(null)
        assertEquals("null follows the platform again", "Light", themeName(day, SuppliedScreen.dark))

        LightSession.getInstance().setAppearance(true)
        SuppliedScreen.clear()
        assertNull("an embedder going away takes its appearance with it", SuppliedScreen.dark)
    }
}
