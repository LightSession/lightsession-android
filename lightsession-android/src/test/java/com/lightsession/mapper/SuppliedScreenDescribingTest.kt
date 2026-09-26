package com.lightsession.mapper

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which description a wireframe is drawn from: the one for the screen the capture is for.
 *
 * Measured with the Flutter example on an emulator, before: every pop filed the page being left under
 * the one returned to — `/form`'s 25 rectangles as the hub — because the destination was scanned
 * while the page being left was still the current screen, and its description matched that.
 */
class SuppliedScreenDescribingTest {

    private fun screen(name: String?) = SuppliedScreen.Screen(
        width = 1080,
        height = 2400,
        root = SuppliedScreen.Node(bounds = Rect(), kind = "CONTAINER"),
        screenName = name,
    )

    @After
    fun tearDown() = SuppliedScreen.clear()

    @Test
    fun nothing_described_is_nothing_to_draw() {
        assertNull(SuppliedScreen.describing("/"))
    }

    @Test
    fun a_description_is_for_the_screen_it_names_whichever_screen_is_current() {
        val form = screen("/form")
        SuppliedScreen.set(form)
        assertSame(form, SuppliedScreen.describing("/form"))
        assertNull("the page being left, filed as the one returned to", SuppliedScreen.describing("/"))
    }

    @Test
    fun a_description_that_names_no_screen_makes_no_claim() {
        val unnamed = screen(null)
        SuppliedScreen.set(unnamed)
        assertSame(unnamed, SuppliedScreen.describing("/"))
    }
}
