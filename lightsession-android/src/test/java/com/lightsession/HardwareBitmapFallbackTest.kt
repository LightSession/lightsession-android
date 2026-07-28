package com.lightsession

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling the hardware-bitmap failure apart from every other draw failure.
 *
 * This decides whether the SDK latches into the slower PixelCopy path for the rest of
 * the process, so both directions matter. Missing it means a screen with images never
 * captures again — the doctor list that prompted this kept its wireframe and dropped
 * sixteen replay frames. Matching too eagerly means every app pays for the fallback
 * because of one unrelated exception.
 *
 * Matched on the message because the platform throws a plain `IllegalArgumentException`
 * with nothing else to distinguish it.
 */
class HardwareBitmapFallbackTest {

    /** Mirrors `ScreenDrawing.isHardwareBitmapFailure`, which is private to that class. */
    private fun isHardwareBitmapFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val message = current.message.orEmpty()
            if (current is IllegalArgumentException && message.contains("hardware bitmap", true)) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    @Test
    fun `the platform's own message is recognised`() {
        // Verbatim from a Galaxy M23 on Android 14, drawing a Coil-loaded avatar.
        val real = IllegalArgumentException("Software rendering doesn't support hardware bitmaps")
        assertTrue(isHardwareBitmapFailure(real))
    }

    @Test
    fun `it is found through a wrapper`() {
        // The throw happens deep inside View.draw and is sometimes rethrown wrapped.
        val wrapped = RuntimeException(
            "drawing failed",
            IllegalArgumentException("Software rendering doesn't support hardware bitmaps")
        )
        assertTrue(isHardwareBitmapFailure(wrapped))
    }

    @Test
    fun `an ordinary draw failure does not latch the fallback`() {
        // OutOfMemory, a recycled bitmap, a null canvas: real failures that PixelCopy
        // would not fix, and switching to it permanently would make things worse.
        for (other in listOf(
            OutOfMemoryError("Failed to allocate"),
            IllegalStateException("Canvas: trying to use a recycled bitmap"),
            IllegalArgumentException("width must be > 0"),
            NullPointerException(),
        )) {
            assertFalse(other.toString(), isHardwareBitmapFailure(other))
        }
    }

    @Test
    fun `a different exception type carrying the same words is not enough`() {
        // The platform throws IllegalArgumentException specifically. Matching on the
        // message alone would catch a log line or a wrapped message that means
        // something else.
        val impostor = IllegalStateException("Software rendering doesn't support hardware bitmaps")
        assertFalse(isHardwareBitmapFailure(impostor))
    }

    @Test
    fun `a cause cycle does not hang the check`() {
        // Self-referencing causes are rare but real, and this runs on the capture path
        // three times a second.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(isHardwareBitmapFailure(a))
    }
}
