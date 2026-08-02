package com.lightsession.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that decide what a tab or a dialog is *called*, and whether it is reported.
 *
 * Worth testing on their own because both failure modes here are silent and expensive.
 * A name that varies between two readings of the same tab does not throw — it quietly
 * doubles the screen, and the duplicate looks like a real screen the app has. A change
 * reported when nothing moved does not throw either; it draws an edge in the flow graph
 * between a screen and itself.
 */
class SubScreensTest {

    // ------------------------------------------------------------- sanitize

    @Test
    fun `a label is trimmed and its whitespace collapsed`() {
        // A tab label that wraps arrives with the newline in it. Left alone, "Recent\nActivity"
        // and "Recent Activity" are two different screen names for one tab.
        assertEquals("Recent Activity", SubScreens.sanitize("  Recent\n  Activity "))
        assertEquals("Overview", SubScreens.sanitize("Overview"))
    }

    @Test
    fun `an empty or blank label is not a label`() {
        assertNull(SubScreens.sanitize(null))
        assertNull(SubScreens.sanitize(""))
        assertNull(SubScreens.sanitize("   \n "))
    }

    @Test
    fun `an over-long label is rejected rather than truncated`() {
        // Length is the signal that the reader grabbed body text instead of a label, and
        // body text is per-user. Truncating would keep the bug and hide it: two dialogs
        // saying "Delete Dr. Silva…" and "Delete Dr. Souza…" would still be two screens.
        val bodyText = "Are you absolutely sure you want to delete this doctor from the system?"
        assertNull(SubScreens.sanitize(bodyText))
        assertEquals(SubScreens.MAX_LABEL, SubScreens.sanitize("a".repeat(32))?.length)
        assertNull(SubScreens.sanitize("a".repeat(33)))
    }

    @Test
    fun `a label cannot smuggle in the separator`() {
        // Otherwise a label could forge a name that splits back apart wrongly, and
        // `dashboard › a › b` would be indistinguishable from a nested sub-screen.
        assertEquals("a b", SubScreens.sanitize("a${SubScreens.SEPARATOR}b"))
    }

    // -------------------------------------------------------------- compose

    @Test
    fun `a sub-screen is appended to the destination`() {
        val tab = SubScreen(SubScreen.Kind.TAB, "History")
        assertEquals("dashboard › History", SubScreens.compose("dashboard", tab))
    }

    @Test
    fun `no sub-screen leaves the destination alone`() {
        assertEquals("dashboard", SubScreens.compose("dashboard", null))
    }

    @Test
    fun `a tab that repeats its destination adds nothing`() {
        // The bottom-nav case, caught by name. `NavigationBarItem` carries the same
        // `Role.Tab` semantics a tab row does, so pressing "Home" reports both a navigation
        // to `home` and a selected tab called "Home" — and `home › Home` would double every
        // bottom-nav screen in the map.
        val tab = SubScreen(SubScreen.Kind.TAB, "Home")
        assertEquals("home", SubScreens.compose("home", tab))
        assertEquals("Home/Manager", SubScreens.compose("Home/Manager", SubScreen(SubScreen.Kind.TAB, "Manager")))
        assertEquals("user_profile", SubScreens.compose("user_profile", SubScreen(SubScreen.Kind.TAB, "User Profile")))
    }

    @Test
    fun `the tab a screen arrived on is filtered out before this`() {
        // Which tab is a sub-screen is settled by diffing against what was selected on
        // arrival, in `ScreenMapperIntegration` — the tab a screen opens on is that screen,
        // and a bottom navigation item never differs from arrival at all. So by the time a
        // tab reaches here it is already known to be the reader's choice, and this only has
        // to name it.
        val history = SubScreen(SubScreen.Kind.TAB, "History")
        assertEquals("dashboard › History", SubScreens.compose("dashboard", history))
    }

    @Test
    fun `a declared panel is a sub-screen like any other`() {
        val declared = SubScreen(SubScreen.Kind.DECLARED, "filter-sheet")
        assertEquals("doctors › filter-sheet", SubScreens.compose("doctors", declared))
    }

    @Test
    fun `a declared name goes through the same sanitising as a read one`() {
        // The app supplies this string, so it is the one most likely to arrive with a
        // newline in it or built out of the data on display.
        assertEquals("filter sheet", SubScreens.sanitize("filter\nsheet"))
        assertNull(SubScreens.sanitize("Delete Dr. Silva from the cardiology department?"))
    }

    @Test
    fun `a tab that only resembles its destination is kept`() {
        val tab = SubScreen(SubScreen.Kind.TAB, "Home Feed")
        assertEquals("home › Home Feed", SubScreens.compose("home", tab))
    }

    // ----------------------------------------------------------- layers

    @Test
    fun `a dialog keeps the tab it was raised from`() {
        // The bug this whole arrangement replaced. With one sub-screen slot, the dialog
        // displaced the tab: opened from each of three tabs it was one node, three
        // heatmaps piled onto one wireframe, and the tab survived only as state to
        // restore on dismissal — never as part of the name.
        val dialog = SubScreen(SubScreen.Kind.MODAL, "dialog-4fab23")
        val names = listOf("Overview", "History", "Settings").map { tab ->
            SubScreens.compose(
                "TabsAndModalActivity",
                listOf(SubScreen(SubScreen.Kind.TAB, tab), dialog),
            )
        }
        assertEquals(
            listOf(
                "TabsAndModalActivity › Overview › dialog-4fab23",
                "TabsAndModalActivity › History › dialog-4fab23",
                "TabsAndModalActivity › Settings › dialog-4fab23",
            ),
            names,
        )
    }

    @Test
    fun `a dialog over the arrival tab sits on the bare destination`() {
        // The arrival tab folds into the base — that tab *is* the screen — so a dialog
        // raised there has no tab layer under it. Consistent, if slightly asymmetric:
        // `dashboard › dialog` from the default tab, `dashboard › History › dialog` from
        // any other.
        assertEquals(
            "dashboard › dialog-4fab23",
            SubScreens.compose("dashboard", listOf(SubScreen(SubScreen.Kind.MODAL, "dialog-4fab23"))),
        )
    }

    @Test
    fun `all three layers nest in their fixed order`() {
        assertEquals(
            "dashboard › History › filters › dialog-4fab23",
            SubScreens.compose(
                "dashboard",
                listOf(
                    SubScreen(SubScreen.Kind.TAB, "History"),
                    SubScreen(SubScreen.Kind.DECLARED, "filters"),
                    SubScreen(SubScreen.Kind.MODAL, "dialog-4fab23"),
                ),
            ),
        )
    }

    @Test
    fun `a layer that repeats the one beneath folds into it`() {
        // A modal named like the tab it covers must not stutter: the redundancy rule reads
        // the name built so far, layer by layer, so `Filter › Filter` never happens.
        assertEquals(
            "doctors › Filter",
            SubScreens.compose(
                "doctors",
                listOf(
                    SubScreen(SubScreen.Kind.TAB, "Filter"),
                    SubScreen(SubScreen.Kind.MODAL, "Filter"),
                ),
            ),
        )
    }

    @Test
    fun `no layers leaves the destination alone`() {
        assertEquals("dashboard", SubScreens.compose("dashboard", emptyList()))
    }
}
