package com.lightsession.errors

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serializer that runs while the process is dying.
 *
 * Every case here is a way a `Throwable` in the wild differs from a `Throwable` in a tutorial:
 * causes that form cycles, stacks thousands of frames deep, messages carrying request bodies.
 * The crash handler is the one place the SDK cannot afford to discover these at runtime, because
 * the discovery would eat the crash it was recording.
 */
class ErrorCrumbTest {

    private fun crumb(
        t: Throwable,
        handled: Boolean = false,
        appPackage: String = "com.app",
    ) = ErrorCrumb.build(t, handled, Thread.currentThread(), appPackage)

    private val exceptions get() = { t: Throwable ->
        crumb(t)["exceptions"]!!.jsonArray
    }

    @Test
    fun `the chain is outermost first`() {
        val root = IllegalArgumentException("root")
        val wrapper = RuntimeException("wrapper", root)

        val chain = exceptions(wrapper)

        assertEquals(2, chain.size)
        assertEquals(
            "java.lang.RuntimeException",
            chain[0].jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "java.lang.IllegalArgumentException",
            chain[1].jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    /**
     * A cause cycle must terminate, not hang. They exist in the wild — a wrapper stored as its
     * own cause's cause — and an unbounded walk here hangs the process on its way down, which
     * converts a crash with a report into an ANR without one.
     */
    @Test
    fun `a cause cycle does not hang the walk`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a

        val chain = exceptions(b)

        // b, then a; the identity set refuses b's second appearance.
        assertEquals(2, chain.size)
    }

    @Test
    fun `the chain is capped`() {
        var t: Throwable = RuntimeException("0")
        repeat(20) { t = RuntimeException("${it + 1}", t) }

        assertEquals(ErrorCrumb.MAX_CAUSES, exceptions(t).size)
    }

    /** A StackOverflowError carries thousands of frames of the same loop. Keep the throw site. */
    @Test
    fun `frames are capped from the top with a marker`() {
        val t = RuntimeException("deep")
        t.stackTrace = Array(500) {
            StackTraceElement("com.app.Loop", "spin", "Loop.kt", it)
        }

        val frames = exceptions(t)[0].jsonObject["frames"]!!.jsonArray

        assertEquals(ErrorCrumb.MAX_FRAMES + 1, frames.size)
        // The first frame is the top of the stack — the throw site.
        assertEquals(0, frames[0].jsonObject["line"]!!.jsonPrimitive.int)
        val marker = frames.last().jsonObject
        assertEquals("…", marker["class"]!!.jsonPrimitive.content)
        assertEquals("380 frames elided", marker["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the message is truncated`() {
        val t = RuntimeException("x".repeat(10_000))

        val message = exceptions(t)[0].jsonObject["message"]!!.jsonPrimitive.content

        assertEquals(ErrorCrumb.MAX_MESSAGE, message.length)
    }

    @Test
    fun `a null message is absent, not the string null`() {
        val t = RuntimeException(null as String?)

        assertNull(exceptions(t)[0].jsonObject["message"])
    }

    /**
     * The boundary check: `com.app` owns `com.app.Foo` but not `com.appother.Foo`. A bare
     * prefix match marks a stranger's package as the app's, and grouping weighs exactly
     * this flag.
     */
    @Test
    fun `in_app respects the package boundary`() {
        val t = RuntimeException("boundaries")
        t.stackTrace = arrayOf(
            StackTraceElement("com.app.Checkout", "pay", "Checkout.kt", 10),
            StackTraceElement("com.appother.Impostor", "lurk", "Impostor.kt", 20),
            StackTraceElement("com.lightsession.SessionDataManager", "addError", "S.kt", 30),
            StackTraceElement("com.app", "topLevel", "App.kt", 40),
        )

        val frames = exceptions(t)[0].jsonObject["frames"]!!.jsonArray
        val inApp = frames.map { it.jsonObject["in_app"]!!.jsonPrimitive.boolean }

        assertEquals(listOf(true, false, false, true), inApp)
    }

    @Test
    fun `a native frame keeps its negative line and missing file`() {
        val t = RuntimeException("native")
        t.stackTrace = arrayOf(
            StackTraceElement("java.lang.Object", "wait", null, -2),
        )

        val frame = exceptions(t)[0].jsonObject["frames"]!!.jsonArray[0].jsonObject

        assertEquals(-2, frame["line"]!!.jsonPrimitive.int)
        assertNull(frame["file"])
    }

    @Test
    fun `handled and thread ride on the crumb`() {
        val built = crumb(RuntimeException("x"), handled = true)

        assertTrue(built["handled"]!!.jsonPrimitive.boolean)
        assertEquals(
            Thread.currentThread().name,
            built["thread"]!!.jsonPrimitive.content,
        )
        assertFalse(built["thread_id"]!!.jsonPrimitive.content.isEmpty())
    }
}
