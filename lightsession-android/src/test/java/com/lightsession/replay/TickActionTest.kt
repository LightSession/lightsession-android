package com.lightsession.replay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The capture tick's decision, one test per case that decided its shape.
 *
 * The first two are the bug. A session whose frames are all repeated-frame markers cannot be
 * rendered — the renderer rejects it permanently, and correctly, because no later delivery reads
 * different bytes. Eighteen sessions reached the dead-letter queue that way, one of them 78 frames
 * with not a single real one among them.
 *
 * The cause was a flag that meant "a capture was asked for" while reading as "a real frame
 * exists". `captureFrame` is asynchronous and answers a failure with a marker, so a first capture
 * that failed cleared the flag and left the session with no first frame it could ever repeat from.
 * `Recorder` now clears it where the bytes are delivered; what is tested here is the other half —
 * that while no frame has been delivered, every tick keeps trying.
 */
class TickActionTest {

    @Test
    fun `captures for the first frame even mid-transition`() {
        // A frame taken mid-transition carries a mask that cannot be made correct, which is why
        // the tick normally declines to take one. It is still the smaller loss: one questionable
        // mask against a session that cannot be rendered at all.
        assertEquals(
            TickAction.Capture,
            tickAction(needsFirstFrame = true, changed = false, transitioning = true),
        )
    }

    @Test
    fun `keeps capturing until a frame is actually delivered`() {
        // Two ticks in a row with nothing changed and no frame yet delivered. Both capture. This
        // is what a failed first capture now looks like from the tick's side, and it is exactly
        // what the old code could not do: it had already spent the flag on the attempt.
        repeat(2) {
            assertEquals(
                TickAction.Capture,
                tickAction(needsFirstFrame = true, changed = false, transitioning = false),
            )
        }
    }

    @Test
    fun `declines to capture through a transition once a frame exists`() {
        assertEquals(
            TickAction.RepeatThroughTransition,
            tickAction(needsFirstFrame = false, changed = false, transitioning = true),
        )
    }

    @Test
    fun `a change during a transition still waits`() {
        // The caller re-arms the change so the next quiet tick takes it; the tick itself must not
        // capture here, or the mask is wrong for the frame that carries the change.
        assertEquals(
            TickAction.RepeatThroughTransition,
            tickAction(needsFirstFrame = false, changed = true, transitioning = true),
        )
    }

    @Test
    fun `captures when the screen changed`() {
        assertEquals(
            TickAction.Capture,
            tickAction(needsFirstFrame = false, changed = true, transitioning = false),
        )
    }

    @Test
    fun `marks a repeat when nothing moved`() {
        assertEquals(
            TickAction.Repeat,
            tickAction(needsFirstFrame = false, changed = false, transitioning = false),
        )
    }

    @Test
    fun `never answers a plain repeat while the first frame is missing`() {
        // The invariant, over the whole input space rather than the cases above: a session with no
        // real frame yet must never be told to emit a marker, because a marker with nothing before
        // it is what the renderer refuses.
        for (changed in listOf(false, true)) {
            for (transitioning in listOf(false, true)) {
                assertEquals(
                    "changed=$changed transitioning=$transitioning",
                    TickAction.Capture,
                    tickAction(needsFirstFrame = true, changed = changed, transitioning = transitioning),
                )
            }
        }
    }
}
