package com.lightsession.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the tab a destination arrived on is the destination itself`() {
        // The duplicate-node bug this parameter exists for. Arriving at `dashboard` names
        // the screen `dashboard`, because the NavController reports the destination before
        // its tabs have composed. Coming back to that same tab later must produce the same
        // name — otherwise one view is filed under both `dashboard` and
        // `dashboard › Overview`, and the map shows a screen the app does not have.
        val overview = SubScreen(SubScreen.Kind.TAB, "Overview")
        val history = SubScreen(SubScreen.Kind.TAB, "History")

        assertEquals("dashboard", SubScreens.compose("dashboard", overview, default = overview))
        assertEquals("dashboard › History", SubScreens.compose("dashboard", history, default = overview))
    }

    @Test
    fun `a localised bottom-nav label is absorbed by the default, not by its name`() {
        // Route `home`, nav item "Início". Nothing about the two strings matches, so the
        // name check cannot help — but the tab is whatever was selected on arrival, which
        // is what makes it the default whatever it is called.
        val inicio = SubScreen(SubScreen.Kind.TAB, "Início")
        assertEquals("home › Início", SubScreens.compose("home", inicio))
        assertEquals("home", SubScreens.compose("home", inicio, default = inicio))
    }

    @Test
    fun `a modal is never absorbed by a tab default`() {
        val tab = SubScreen(SubScreen.Kind.TAB, "Filter")
        val modal = SubScreen(SubScreen.Kind.MODAL, "Filter")
        assertEquals("doctors › Filter", SubScreens.compose("doctors", modal, default = tab))
    }

    @Test
    fun `a declared panel is a sub-screen like any other`() {
        val declared = SubScreen(SubScreen.Kind.DECLARED, "filter-sheet")
        assertEquals("doctors › filter-sheet", SubScreens.compose("doctors", declared))
        assertTrue(SubScreens.shouldReport(null, declared))
        assertTrue(SubScreens.shouldReport(declared, null))
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

    // --------------------------------------------------------- shouldReport

    @Test
    fun `moving between tabs is reported`() {
        assertTrue(
            SubScreens.shouldReport(
                previous = SubScreen(SubScreen.Kind.TAB, "Overview"),
                next = SubScreen(SubScreen.Kind.TAB, "History"),
            ),
        )
    }

    @Test
    fun `re-reading the same tab is not a change`() {
        // This runs after every touch, so most reads return what the last one did.
        val same = SubScreen(SubScreen.Kind.TAB, "Overview")
        assertFalse(SubScreens.shouldReport(same, same.copy()))
    }

    @Test
    fun `opening and closing a modal are both reported`() {
        val modal = SubScreen(SubScreen.Kind.MODAL, "confirm-delete")
        assertTrue(SubScreens.shouldReport(null, modal))
        assertTrue(SubScreens.shouldReport(modal, null))
    }

    @Test
    fun `a tab and a modal with the same label are different sub-screens`() {
        assertTrue(
            SubScreens.shouldReport(
                previous = SubScreen(SubScreen.Kind.TAB, "Filter"),
                next = SubScreen(SubScreen.Kind.MODAL, "Filter"),
            ),
        )
    }

}
