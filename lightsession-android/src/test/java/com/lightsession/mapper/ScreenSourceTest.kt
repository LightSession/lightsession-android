package com.lightsession.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole decision space for where an Activity's screen names come from.
 *
 * Four cases, and two of them were wrong for nineteen commits with nothing failing — because the
 * decision was nested `if`s over a live view hierarchy and a FragmentManager, which no JVM test can
 * build. Reduced to two booleans it is exhaustible, and this walks all of it.
 */
class ScreenSourceTest {

    /**
     * A plain Activity is its own screen.
     *
     * The case that always worked, and the one most likely to be broken by a change to the others.
     */
    @Test
    fun `neither compose nor a nav host means the activity is the screen`() {
        val plan = planScreenSource(usesCompose = false, hasConventionalNavHost = false)

        assertTrue("the Activity's own name is all there is", plan.reportActivityNow)
        assertFalse(plan.registerConventionalNav)
        assertFalse(plan.resolveComposeAfterGrace)
    }

    /**
     * One Activity holding one Compose screen is a screen.
     *
     * It used to be nothing at all: the Compose check short-circuited the only branch that reports
     * an Activity by name. Measured on the sample app — a screen sat on for thirty-seven seconds
     * did not exist, and the flow through it read as a single navigation straight past it.
     */
    @Test
    fun `compose without a nav host defers rather than reporting nothing`() {
        val plan = planScreenSource(usesCompose = true, hasConventionalNavHost = false)

        assertTrue(
            "a Compose Activity has to end up with a screen, by destination or by its own name",
            plan.resolveComposeAfterGrace,
        )
        assertFalse(
            "reporting now would leave a node per Activity beside a node per destination",
            plan.reportActivityNow,
        )
    }

    /** Fragment destinations name the screens, and the Activity name must not be added to them. */
    @Test
    fun `a conventional nav host supplies the screens`() {
        val plan = planScreenSource(usesCompose = false, hasConventionalNavHost = true)

        assertTrue(plan.registerConventionalNav)
        assertFalse(plan.reportActivityNow)
        assertFalse(plan.resolveComposeAfterGrace)
    }

    /**
     * A hybrid Activity still registers its fragment destinations.
     *
     * This is the one that went dark. The Compose check ran first and skipped the fragment
     * registration, and a fragment's views hang under the Activity's content view — so a single
     * Compose screen anywhere in a migrating app made `usesCompose` true and took the whole
     * Activity down the Compose path. Its fragment navigation was never listened to.
     *
     * It was also non-deterministic: the check runs once, at resume, so which path an Activity took
     * depended on which fragment was on screen when it resumed. Background the app on a Compose
     * screen and the same build behaved differently from backgrounding it on an XML one.
     */
    @Test
    fun `a nav host plus compose registers the nav host anyway`() {
        val plan = planScreenSource(usesCompose = true, hasConventionalNavHost = true)

        assertTrue(
            "one ComposeView in a migrating app must not silence fragment navigation",
            plan.registerConventionalNav,
        )
        assertFalse(
            "the destinations are the screens; a Compose screen inside one is part of it",
            plan.reportActivityNow,
        )
        assertFalse(plan.resolveComposeAfterGrace)
    }

    /**
     * Every case yields exactly one source of screen names.
     *
     * Stated over the whole space rather than per case, because the failure both bugs shared was an
     * Activity ending up with *no* source — which no individual assertion about a branch would have
     * caught, since each branch looked reasonable on its own.
     */
    @Test
    fun `every combination has exactly one source of screen names`() {
        for (usesCompose in listOf(false, true)) {
            for (hasNavHost in listOf(false, true)) {
                val plan = planScreenSource(usesCompose, hasNavHost)
                val sources = listOf(
                    plan.registerConventionalNav,
                    plan.resolveComposeAfterGrace,
                    plan.reportActivityNow,
                ).count { it }

                assertEquals(
                    "compose=$usesCompose navHost=$hasNavHost produced $sources sources",
                    1,
                    sources,
                )
            }
        }
    }

    /** Two reports would put a node per Activity in a permanent map beside a node per screen. */
    @Test
    fun `no combination both defers to compose and reports the activity`() {
        for (usesCompose in listOf(false, true)) {
            for (hasNavHost in listOf(false, true)) {
                val plan = planScreenSource(usesCompose, hasNavHost)
                assertFalse(
                    "compose=$usesCompose navHost=$hasNavHost double-reports",
                    plan.resolveComposeAfterGrace && plan.reportActivityNow,
                )
            }
        }
    }
}
