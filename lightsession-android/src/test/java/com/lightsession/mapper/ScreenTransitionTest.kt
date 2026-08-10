package com.lightsession.mapper

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cases that decided this class's shape, one test each.
 *
 * The first is the one that matters: it is the bug. A scrolling list keeps the composition busy
 * forever and used to be indistinguishable from a crossfade, so the recorder suppressed the whole
 * scroll — 1 real frame against 196 repeat markers over twenty seconds, measured on a tablet.
 */
class ScreenTransitionTest {

    private val t0 = 1_000_000L

    @Before
    fun setUp() {
        ScreenTransition.reset()
        CompositionActivity.resetForTest()
    }

    @After
    fun tearDown() {
        ScreenTransition.reset()
        CompositionActivity.resetForTest()
    }

    /** The bug. Movement without a navigation is somebody scrolling, and it must not suppress. */
    @Test
    fun a_busy_composition_alone_is_not_a_transition() {
        CompositionActivity.noteChangeForTest(t0)

        assertFalse(
            "a composition that is merely busy — a scroll, an animation — was read as a screen " +
                "change, which is what swallowed entire scrolls",
            ScreenTransition.inProgress(t0 + 10),
        )
    }

    /** A crossfade: the mapper announced a destination and the composition is working. */
    @Test
    fun a_navigation_with_a_busy_composition_is_a_transition() {
        ScreenTransition.begin(t0)
        CompositionActivity.noteChangeForTest(t0 + 200)

        assertTrue(
            "a real screen change was not suppressed; a frame from here shows two screens",
            ScreenTransition.inProgress(t0 + 250),
        )
    }

    /**
     * The window opens before the arriving screen exists.
     *
     * A `NavController` reports the destination before its content composes, so for a moment after
     * [ScreenTransition.begin] there is genuinely nothing moving to detect.
     */
    @Test
    fun the_window_survives_the_gap_before_the_new_screen_composes() {
        ScreenTransition.begin(t0)

        assertTrue(
            "the transition ended before the arriving screen had composed anything",
            ScreenTransition.inProgress(t0 + 50),
        )
    }

    /** Once the crossfade stops, capture has to resume immediately. */
    @Test
    fun a_settled_composition_ends_the_transition() {
        ScreenTransition.begin(t0)
        CompositionActivity.noteChangeForTest(t0 + 300)

        assertFalse(
            "the composition went quiet 200ms ago and the transition is still suppressing",
            ScreenTransition.inProgress(t0 + 500),
        )
    }

    /**
     * The spinner case. A screen whose composition never settles would otherwise suppress for as
     * long as the user stayed on it.
     */
    @Test
    fun a_composition_that_never_settles_is_capped() {
        ScreenTransition.begin(t0)

        var now = t0
        repeat(60) {
            now += 100
            CompositionActivity.noteChangeForTest(now)
        }

        assertTrue("the fixture stopped animating", now - t0 > 2_000L)
        assertFalse(
            "a screen that never stops animating suppressed capture past the cap — navigate to " +
                "anything with a loading spinner and nothing is recorded",
            ScreenTransition.inProgress(now),
        )
    }

    /** Nothing announced, nothing moving: the common case, and it must be cheap and false. */
    @Test
    fun a_quiet_screen_is_not_a_transition() {
        assertFalse(ScreenTransition.inProgress(t0))
    }
}
