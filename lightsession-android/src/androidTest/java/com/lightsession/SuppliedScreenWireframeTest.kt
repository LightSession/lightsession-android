package com.lightsession

import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.SkeletonGenerator
import com.lightsession.mapper.SuppliedScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a screen an embedder describes becomes the wireframe a walked screen becomes.
 *
 * Without this seam, every screen of a surface-rendering app reaches the dashboard as one grey
 * rectangle the size of the display — the walk finds a single `FlutterView` with no children, and
 * that is not an error, so nothing logs and nothing looks broken until somebody opens the picture.
 *
 * On a device rather than the JVM, and not for convenience: this module's unit tests run with
 * `isReturnDefaultValues`, under which `Rect(1, 2, 3, 4).left` is `0`. A geometry test there would
 * pass while proving nothing.
 */
@RunWith(AndroidJUnit4::class)
class SuppliedScreenWireframeTest {

    private companion object {
        const val BACKGROUND = Color.WHITE

        /** What the palette paints a filled leaf container, and a stroked one with children. */
        const val LEAF_CONTAINER = 0xFFE0E0E0.toInt()
        const val PARENT_CONTAINER = 0xFFBDBDBD.toInt()
    }

    @After
    fun tearDown() {
        // Process-wide, so a leaked description would reach every later test in this run.
        SuppliedScreen.clear()
    }

    private fun node(
        kind: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int? = null,
        children: List<SuppliedScreen.Node> = emptyList(),
    ) = SuppliedScreen.Node(Rect(left, top, right, bottom), kind, color, children)

    @Test
    fun a_described_screen_becomes_the_rectangles_it_describes() {
        val screen = SuppliedScreen.Screen(
            width = 1080,
            height = 2400,
            root = node(
                "CONTAINER", 0, 0, 1080, 2400,
                children = listOf(
                    node("TEXT", 40, 100, 600, 160),
                    node("INPUT", 40, 200, 1040, 320),
                    node("IMAGE", 40, 400, 200, 560),
                ),
            ),
        )

        val frame = SkeletonGenerator().frameFrom(screen, BACKGROUND)
        assertNotNull("no wireframe came back for a described screen", frame)

        val rects = frame!!.rects
        assertEquals("the root and its three children", 4, rects.size)

        // Pre-order, which is the order the server paints in: a parent cannot land on top of its
        // own children.
        assertEquals("CONTAINER", rects[0].kind)
        assertEquals(listOf("TEXT", "INPUT", "IMAGE"), rects.drop(1).map { it.kind })

        // The coordinates are the ones described, not a scaled or offset version of them.
        val text = rects[1]
        assertEquals(40, text.left)
        assertEquals(100, text.top)
        assertEquals(600, text.right)
        assertEquals(160, text.bottom)

        assertEquals(1080, frame.width)
        assertEquals(2400, frame.height)
    }

    @Test
    fun a_container_is_an_outline_when_it_holds_something_and_a_fill_when_it_does_not() {
        // The rule that makes a supplied wireframe readable. A filled container hides everything
        // inside it, so a parent is drawn as an outline — and the same rule already governs a
        // natively walked screen, which is why it is reached rather than restated.
        val screen = SuppliedScreen.Screen(
            width = 400,
            height = 400,
            root = node(
                "CONTAINER", 0, 0, 400, 400,
                children = listOf(node("CONTAINER", 10, 10, 100, 100)),
            ),
        )

        val rects = SkeletonGenerator().frameFrom(screen, BACKGROUND)!!.rects
        assertEquals(2, rects.size)

        assertTrue("a container holding a child should be an outline", rects[0].stroke)
        assertEquals(PARENT_CONTAINER, rects[0].color)

        assertFalse("a container holding nothing should be filled", rects[1].stroke)
        assertEquals(LEAF_CONTAINER, rects[1].color)
    }

    @Test
    fun a_declared_colour_wins_and_is_painted_rather_than_outlined() {
        // An embedder declaring a colour is saying this rectangle is painted, which an outline
        // would contradict. It is also the only colour information the palette cannot invent.
        val brand = 0xFF3F51B5.toInt()
        val screen = SuppliedScreen.Screen(
            width = 400,
            height = 400,
            root = node(
                "CONTAINER", 0, 0, 400, 400, color = brand,
                children = listOf(node("TEXT", 10, 10, 100, 40)),
            ),
        )

        val root = SkeletonGenerator().frameFrom(screen, BACKGROUND)!!.rects.first()
        assertEquals(brand, root.color)
        assertFalse("a declared colour is a fill", root.stroke)
    }

    @Test
    fun a_kind_this_build_does_not_know_degrades_instead_of_failing() {
        // The server has the same rule for a name it does not know. A wireframe with one odd
        // rectangle is worth more than no wireframe, and an embedder from a newer version of the
        // plugin must not be able to take the picture down.
        val screen = SuppliedScreen.Screen(
            width = 400,
            height = 400,
            root = node("SOMETHING_NEW", 0, 0, 400, 400),
        )

        val rects = SkeletonGenerator().frameFrom(screen, BACKGROUND)!!.rects
        assertEquals(1, rects.size)
        assertEquals("UNKNOWN", rects.single().kind)
    }

    @Test
    fun a_screen_with_no_size_produces_nothing() {
        val screen = SuppliedScreen.Screen(0, 0, node("CONTAINER", 0, 0, 0, 0))
        assertNull(SkeletonGenerator().frameFrom(screen, BACKGROUND))
    }

    @Test
    fun an_empty_rectangle_is_dropped_but_its_children_are_not() {
        // A rectangle with no area draws nothing anywhere, so sending one costs bytes to say
        // nothing. Its children are a separate question — a degenerate parent says nothing about
        // what is inside it.
        val screen = SuppliedScreen.Screen(
            width = 400,
            height = 400,
            root = node(
                "CONTAINER", 0, 0, 400, 400,
                children = listOf(
                    node(
                        "CONTAINER", 50, 50, 50, 200,
                        children = listOf(node("TEXT", 60, 60, 300, 90)),
                    ),
                ),
            ),
        )

        val rects = SkeletonGenerator().frameFrom(screen, BACKGROUND)!!.rects
        assertEquals("the zero-width container is gone, the root and the text remain", 2, rects.size)
        assertEquals(listOf("CONTAINER", "TEXT"), rects.map { it.kind })
    }
}
