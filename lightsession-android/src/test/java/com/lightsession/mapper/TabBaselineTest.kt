package com.lightsession.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrival-state rules that keep a bottom-nav label from becoming a screen.
 *
 * The failure these pin was found in a production map, once, with one interaction, as a
 * permanent `feed › Timeline` node — the destination's own nav label reported as a part of
 * itself. It took a tap inside the settle window to produce, which is why no test that
 * drives real timing would hold it down reliably; what the tests hold down instead is the
 * invariant that makes the timing irrelevant.
 */
class TabBaselineTest {

    // The production shape: a bottom-nav destination whose route id shares no characters
    // with its visible label, so the redundancy fold downstream cannot save us.
    private val navLabel = "Timeline"

    @Test
    fun `the first read is the arrival state, whoever scheduled it`() {
        // The race: a tap lands inside the settle window, its read fires first, and the
        // arrival read it displaced never runs. That first read must learn, not choose —
        // choosing against an empty baseline is what minted `feed › Timeline`.
        val baseline = TabBaseline()
        baseline.reset()
        assertFalse(baseline.learned)

        baseline.learn(listOf(navLabel))
        assertTrue(baseline.learned)

        // The next read finds the same selection; nothing moved, nothing is chosen.
        assertNull(baseline.choose(listOf(navLabel)))
    }

    @Test
    fun `the tab a screen arrives on is the screen, not a part of it`() {
        val baseline = TabBaseline()
        baseline.learn(listOf(navLabel, "Overview"))
        // Both were selected on arrival — the nav item and the tab row's default.
        assertNull(baseline.choose(listOf(navLabel, "Overview")))
    }

    @Test
    fun `a tab selected after arrival is the one chosen`() {
        val baseline = TabBaseline()
        baseline.learn(listOf(navLabel, "Overview"))
        // The person moved from Overview to History; the nav item stayed put and drops
        // out of the diff without having to be recognised as a nav item.
        assertEquals("History", baseline.choose(listOf(navLabel, "History")))
    }

    @Test
    fun `an empty read keeps the learning window open`() {
        // The arrival read is delayed because content composes after the destination is
        // reported — but composition can outlast the delay too. Freezing an empty read as
        // the baseline would leave every later read one diff away from the phantom.
        val baseline = TabBaseline()
        baseline.learn(emptyList())
        assertFalse(baseline.learned)

        // The next read sees the composed screen and becomes the real baseline.
        baseline.learn(listOf(navLabel))
        assertTrue(baseline.learned)
        assertNull(baseline.choose(listOf(navLabel)))
    }

    @Test
    fun `a navigation forgets the previous destination's arrival state`() {
        val baseline = TabBaseline()
        baseline.learn(listOf("Members"))
        baseline.reset()
        assertFalse(baseline.learned)
        assertTrue(baseline.defaults.isEmpty())
    }

    @Test
    fun `a screen with no tabs never chooses anything`() {
        // Every read on such a screen lands in the learning branch, which reads as odd and
        // is exactly right: there is nothing to baseline and nothing to report, and the
        // caller's fall-through still applies the layers a declared panel may have left.
        val baseline = TabBaseline()
        baseline.learn(emptyList())
        baseline.learn(emptyList())
        assertFalse(baseline.learned)
    }
}
