package com.lightsession.replay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a freshly encoded frame is allowed to go out as a repeat marker instead.
 *
 * The decision moved here from before the capture, and only for one reason: a window whose
 * content is a surface has to be captured on every tick. The draw listener that normally answers
 * "did anything change" is deaf to a surface paint — measured on a Flutter session, it reported
 * a still screen through twenty seconds of scrolling and navigation — so the recorder stops
 * trusting it there and asks the bytes afterwards instead.
 *
 * Which puts the whole economy of the repeated-frame signal on this one function, plus the rule
 * that keeps a session renderable at all.
 */
class RepeatedFrameTest {

    private val frame = byteArrayOf(1, 2, 3, 4)
    private val differentFrame = byteArrayOf(1, 2, 3, 5)

    @Test
    fun `an identical frame is a repeat`() {
        assertTrue(
            isRepeatOfLastFrame(
                bytes = frame.copyOf(),
                isFirstFrame = false,
                lastDelivered = frame,
            ),
        )
    }

    @Test
    fun `a frame differing by one byte is not a repeat`() {
        assertFalse(
            isRepeatOfLastFrame(
                bytes = differentFrame,
                isFirstFrame = false,
                lastDelivered = frame,
            ),
        )
    }

    @Test
    fun `the first frame of a stretch is never a repeat, however identical`() {
        // The rule that outranks the economy. A stretch whose first delivery is a marker is a
        // session the renderer refuses — it says "the same as the one before" with nothing
        // before it — and no later frame repairs it, because no later delivery reads different
        // bytes. Eighteen production sessions were dead-lettered that way.
        assertFalse(
            isRepeatOfLastFrame(
                bytes = frame.copyOf(),
                isFirstFrame = true,
                lastDelivered = frame,
            ),
        )
    }

    @Test
    fun `nothing is a repeat when nothing has been delivered`() {
        assertFalse(
            isRepeatOfLastFrame(bytes = frame, isFirstFrame = false, lastDelivered = null),
        )
    }

    @Test
    fun `a failed encode is not a repeat`() {
        // Null means the encode threw. It has its own handling downstream, where it counts as a
        // delivery that stores nothing; turning it into a marker here would claim the screen was
        // unchanged on the strength of an error.
        assertFalse(
            isRepeatOfLastFrame(bytes = null, isFirstFrame = false, lastDelivered = frame),
        )
    }
}
