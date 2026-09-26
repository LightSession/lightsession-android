package com.lightsession.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What tells a description that changed from one merely sent again.
 *
 * An embedder describes its screen whenever it paints, and every description used to be sent: 16 of
 * 24 wireframe sends across six navigations of the Flutter example were the layout the slot already
 * held. Colours are sampled per capture, so they are not the layout.
 */
class LayoutKeyTest {

    private fun rect(top: Int, kind: String = "TEXT", color: Int = 0) =
        SkeletonRect(left = 48, top = top, right = 600, bottom = top + 72, kind = kind, color = color, stroke = false)

    private fun frame(vararg rects: SkeletonRect) =
        SkeletonFrame(width = 1080, height = 2400, background = 0, rects = rects.toList())

    @Test
    fun the_same_layout_in_other_colours_is_the_same_layout() {
        assertEquals(
            frame(rect(300, color = 0xFFFFFFFF.toInt())).layoutKey(),
            frame(rect(300, color = 0xFF000000.toInt())).layoutKey(),
        )
    }

    @Test
    fun a_rectangle_moved_retyped_or_added_is_another_layout() {
        val key = frame(rect(300)).layoutKey()
        assertNotEquals(key, frame(rect(302)).layoutKey())
        assertNotEquals(key, frame(rect(300, kind = "IMAGE")).layoutKey())
        assertNotEquals(key, frame(rect(300), rect(400)).layoutKey())
    }
}
