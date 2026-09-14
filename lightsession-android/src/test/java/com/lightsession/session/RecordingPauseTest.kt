package com.lightsession.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who wins when the lifecycle and the app disagree about whether recording is on.
 *
 * The rule is one sentence: **the app's word outranks the lifecycle's.** Backgrounding pauses a
 * running recorder and foregrounding resumes it — but only when the pause is the lifecycle's own,
 * and never over an explicit `stopRecording`. The sequence pinned here shipped broken once:
 * stop-while-paused hit an early return, left the pause flag standing, and the next foreground
 * resumed a recorder the app had just asked to stop.
 *
 * These transitions are plain state on a JVM-visible object, which is why this is a unit test and
 * not an instrumented one: the bug was ordering, not Android.
 */
class RecordingPauseTest {

    @Before
    fun freshStart() {
        Recording.appOverrides()
        Recording.enabled = true
    }

    @After
    fun restore() {
        Recording.appOverrides()
        Recording.enabled = true
    }

    @Test
    fun `background pauses and foreground resumes`() {
        Recording.pauseForBackground()
        assertFalse(Recording.enabled)

        assertTrue(Recording.resumeFromBackground())
        assertTrue(Recording.enabled)
    }

    @Test
    fun `an explicit stop while paused sticks across the next foreground`() {
        // The shipped bug, step by step. Background pauses:
        Recording.pauseForBackground()
        assertFalse(Recording.enabled)

        // The app says stop — from a worker, a push handler, a settings toggle. This is what
        // LightSession.stopRecording does before consulting `enabled`.
        Recording.appOverrides()

        // Foreground must NOT resume: the pause is no longer the reason recording is off.
        assertFalse("foreground resumed a recorder the app explicitly stopped", Recording.resumeFromBackground())
        assertFalse(Recording.enabled)
    }

    @Test
    fun `a recorder the app stopped before backgrounding is not adopted by the pause`() {
        // App stops first; recording is off on the app's authority.
        Recording.enabled = false
        Recording.appOverrides()

        // Backgrounding must not claim it: pauseForBackground only pauses a *running* recorder.
        Recording.pauseForBackground()
        assertFalse("backgrounding adopted a stop that belongs to the app", Recording.resumeFromBackground())
        assertFalse(Recording.enabled)
    }

    @Test
    fun `resume is idempotent`() {
        Recording.pauseForBackground()
        assertTrue(Recording.resumeFromBackground())
        // A second foreground without a background in between has nothing to resume.
        assertFalse(Recording.resumeFromBackground())
        assertTrue(Recording.enabled)
    }
}
