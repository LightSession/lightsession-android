package com.lightsession

import com.lightsession.mapper.SkeletonFrame
import com.lightsession.mapper.SkeletonRect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format of a skeleton capture.
 *
 * Worth a test of its own because it is a contract with a *different codebase*: the
 * server draws these rectangles in `ls_media::skeleton`, and nothing on either side
 * fails loudly if the two drift. A renamed key or a swapped colour channel produces a
 * wireframe that is merely wrong — no exception, no log line, just a picture of a
 * screen that does not exist.
 */
class SkeletonFrameTest {

    private fun rect(
        left: Int = 10,
        top: Int = 20,
        right: Int = 110,
        bottom: Int = 60,
        kind: String = "BUTTON",
        color: Int = 0xFF9C27B0.toInt(),
        stroke: Boolean = false,
    ) = SkeletonRect(left, top, right, bottom, kind, color, stroke)

    private fun frame(vararg rects: SkeletonRect) = SkeletonFrame(
        width = 1080,
        height = 2400,
        background = 0xFFFAF8FF.toInt(),
        rects = rects.toList(),
    )

    @Test
    fun `a rect serialises to the keys the server reads`() {
        val json = rect().toJson()

        // `l/t/r/b`, the same order as android.graphics.Rect.
        assertEquals(10, json.getInt("l"))
        assertEquals(20, json.getInt("t"))
        assertEquals(110, json.getInt("r"))
        assertEquals(60, json.getInt("b"))
        assertEquals("BUTTON", json.getString("kind"))
        assertEquals("#9C27B0", json.getString("color"))
    }

    @Test
    fun `stroke is omitted when false`() {
        // Most nodes are fills, and there is one of these per widget per screen. The
        // server defaults the field, so sending it would be five bytes of nothing.
        assertFalse(rect(stroke = false).toJson().has("stroke"))
        assertTrue(rect(stroke = true).toJson().getBoolean("stroke"))
    }

    @Test
    fun `an opaque colour is six hex digits and a translucent one is eight`() {
        // Two bytes per widget on the common case.
        assertEquals("#9C27B0", SkeletonFrame.colorToHex(0xFF9C27B0.toInt()))
        assertEquals("#809C27B0", SkeletonFrame.colorToHex(0x809C27B0.toInt()))
    }

    @Test
    fun `colours are written in android's channel order not css's`() {
        // `#AARRGGBB`, not `#RRGGBBAA`. Reading one as the other swaps alpha with red,
        // which shows up as a wireframe tinted red rather than as an error — so it
        // would survive a casual look at the dashboard.
        assertEquals("#80FF0000", SkeletonFrame.colorToHex(0x80FF0000.toInt()))
        assertEquals("#800000FF", SkeletonFrame.colorToHex(0x800000FF.toInt()))
    }

    @Test
    fun `a fully transparent colour keeps its alpha rather than collapsing to opaque`() {
        // Alpha 0 must not take the six-digit branch: the server would draw an opaque
        // black rectangle where the device meant nothing at all.
        assertEquals("#00000000", SkeletonFrame.colorToHex(0x00000000))
    }

    @Test
    fun `a frame carries its dimensions background and nodes`() {
        val json = frame(rect(), rect(left = 0, kind = "CONTAINER", stroke = true)).toJson()

        assertEquals(1080, json.getInt("width"))
        assertEquals(2400, json.getInt("height"))
        assertEquals("#FAF8FF", json.getString("background"))
        assertEquals(2, json.getJSONArray("nodes").length())
    }

    @Test
    fun `node order is preserved because it is the paint order`() {
        // The tree is flattened pre-order and the server paints in sequence — that is
        // the only thing that puts a child on top of its parent. A reordering here
        // would bury foreground widgets under their containers.
        val json = frame(
            rect(kind = "CONTAINER", stroke = true),
            rect(kind = "CARD"),
            rect(kind = "TEXT"),
        ).toJson()

        val kinds = json.getJSONArray("nodes").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("kind") }
        }
        assertEquals(listOf("CONTAINER", "CARD", "TEXT"), kinds)
    }

    @Test
    fun `an empty frame is still a valid payload`() {
        // A screen captured before anything laid out. A blank wireframe is a true
        // statement; refusing to serialise it would lose the screen from the graph.
        val json = frame().toJson()
        assertEquals(0, json.getJSONArray("nodes").length())
    }

    @Test
    fun `the payload for a realistic screen is a fraction of a screenshot`() {
        // The claim the whole mode rests on: a JPEG of a 1080x2400 frame measured
        // 80 KB on device (see MaskingCostTest), and this replaces it.
        val rects = (0 until 40).map { index ->
            rect(top = 120 + index * 60, bottom = 168 + index * 60)
        }
        val bytes = frame(*rects.toTypedArray()).toJson().toString().toByteArray().size

        assertTrue(
            "40 widgets serialised to $bytes bytes",
            bytes < 8 * 1024
        )
    }

    @Test
    fun `the payload survives a parse, so it is really json`() {
        // `toString` on a hand-assembled JSONObject is where an unescaped value would
        // show up, and the server would reject the whole screen for it.
        val text = frame(rect(kind = "WEBVIEW")).toJson().toString()
        val reparsed = JSONObject(text)
        assertEquals("WEBVIEW", reparsed.getJSONArray("nodes").getJSONObject(0).getString("kind"))
    }
}
