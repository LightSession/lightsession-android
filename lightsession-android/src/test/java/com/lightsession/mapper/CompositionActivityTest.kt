package com.lightsession.mapper

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal the recorder uses to know a screen is still changing.
 *
 * Worth a test of its own because the whole point of it is a decision the recorder makes several
 * times a second, and the two ways to get it wrong are opposites: too eager and a session records
 * nothing but repeats, too reluctant and the frames that straddle a navigation come back.
 */
class CompositionActivityTest {

    @After
    fun tearDown() = CompositionActivity.resetForTest()

    @Test
    fun a_change_just_now_counts_as_moving() {
        CompositionActivity.noteChangeForTest(1_000)
        assertTrue(CompositionActivity.movingWithin(120, now = 1_050))
    }

    @Test
    fun a_change_older_than_the_window_is_settled() {
        CompositionActivity.noteChangeForTest(1_000)
        assertFalse(CompositionActivity.movingWithin(120, now = 1_200))
    }

    @Test
    fun the_boundary_is_exclusive_so_the_window_cannot_last_forever() {
        CompositionActivity.noteChangeForTest(1_000)
        assertFalse("exactly at the edge is settled", CompositionActivity.movingWithin(120, now = 1_120))
    }

    @Test
    fun nothing_watched_is_never_moving() {
        // The case that matters most: a host without Compose, or one where the observer could
        // not be registered. Reporting movement there would gate every capture forever and the
        // session would be one frame followed by repeats — recording *nothing*, quietly.
        CompositionActivity.resetForTest()
        assertFalse(CompositionActivity.movingWithin(120, now = 1_000))
        assertFalse(CompositionActivity.movingWithin(120, now = Long.MAX_VALUE))
    }

    @Test
    fun watching_without_a_change_yet_is_not_moving() {
        // Registered but nothing has happened. A zero timestamp is "no data", not "changed at
        // the epoch" — which, subtracted from now, would read as very old and settled anyway,
        // but only by accident.
        CompositionActivity.noteChangeForTest(0)
        assertFalse(CompositionActivity.movingWithin(120, now = 5_000))
    }
}
