package com.lightsession

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lightsession.session.Recording

/**
 * The default, and the fact that the host app cannot write it.
 *
 * Small on purpose. The switch itself is one boolean; what is worth pinning is the pair of
 * decisions around it, both of which are the kind that get reversed by someone who does not know
 * why they were made.
 */
class RecordingTest {

    @After
    fun tearDown() {
        Recording.enabled = true
    }

    @Test
    fun recording_is_on_until_something_turns_it_off() {
        // The default has to match every version before this switch existed. An SDK that stopped
        // recording on upgrade, because a new flag defaulted off, would be a silent outage in
        // every app that took the update — and the symptom is an empty dashboard, which reads as
        // "our SDK is broken" rather than as "a default changed".
        assertTrue(Recording.enabled)
    }

    @Test
    fun turning_it_off_and_on_is_just_the_flag() {
        // Nothing here is clever; the point is that the flag has no other state beside it, so a
        // producer reading it cannot see a stale answer. What the switch *does* — rolling the
        // session, flushing — belongs to `LightSession`, and is asserted against the real
        // pipeline in `RecordingGateTest` because it needs a Context to mean anything.
        Recording.enabled = false
        assertFalse(Recording.enabled)
        Recording.enabled = true
        assertTrue(Recording.enabled)
    }
}
