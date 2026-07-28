package com.lightsession

import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.SubScreen
import com.lightsession.mapper.SubScreenReader
import curtains.Curtains
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the readers get the right answer out of a real composition on a real device.
 *
 * `ComposeOverlayProbeTest` establishes what the *framework* publishes; this is about what
 * the SDK makes of it. The two claims that matter most are the ones a wrong implementation
 * would still pass a glance on: that a dropdown menu is not mistaken for a screen, and that
 * a dialog whose text changes per user keeps one name.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.SubScreenReaderTest
 */
@RunWith(AndroidJUnit4::class)
class SubScreenReaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "SubScreenReader"
    }

    private fun decor(): View = compose.activity.window.decorView

    /** The most recently added window, which is where a modal lives. */
    private fun newestWindow(): View = Curtains.rootViews.last()

    // -------------------------------------------------------------------- tabs

    @Test
    fun reads_the_selected_tab_and_follows_a_click() {
        val labels = listOf("Overview", "History", "Notes")
        compose.setContent {
            var selected by remember { mutableStateOf(0) }
            Column {
                TabRow(selectedTabIndex = selected) {
                    labels.forEachIndexed { index, label ->
                        Tab(
                            selected = selected == index,
                            onClick = { selected = index },
                            text = { Text(label) },
                        )
                    }
                }
                Text("body of ${labels[selected]}")
            }
        }
        compose.waitForIdle()

        assertEquals("Overview", SubScreenReader.selectedTab(decor()))
        compose.onNodeWithText("Notes").performClick()
        compose.waitForIdle()
        assertEquals("Notes", SubScreenReader.selectedTab(decor()))

        val started = System.nanoTime()
        repeat(50) { SubScreenReader.selectedTab(decor()) }
        Log.i(TAG, "selectedTab: %.0f us".format((System.nanoTime() - started) / 50 / 1_000.0))
    }

    /**
     * The reason the read is hung off the end of a touch rather than off a click.
     *
     * With a pager the tab moves on a horizontal drag and nothing is ever tapped in the tab
     * row, so a detector wired to clicks reports the tab the user swiped away from.
     */
    @Test
    fun follows_a_pager_swipe() {
        val labels = listOf("Overview", "History")
        compose.setContent {
            val pager = rememberPagerState { labels.size }
            Column {
                TabRow(selectedTabIndex = pager.currentPage) {
                    labels.forEachIndexed { index, label ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = {},
                            text = { Text(label) },
                        )
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("pager")) {
                    Text("body of ${labels[it]}")
                }
            }
        }
        compose.waitForIdle()

        assertEquals("Overview", SubScreenReader.selectedTab(decor()))
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("History", SubScreenReader.selectedTab(decor()))
    }

    @Test
    fun a_screen_without_tabs_reads_as_no_tab() {
        compose.setContent { Text("Doctors") }
        compose.waitForIdle()
        assertNull(SubScreenReader.selectedTab(decor()))
    }

    // ------------------------------------------------------------------ modals

    @Test
    fun a_dialog_is_a_modal_and_its_test_tag_names_it() {
        var open by mutableStateOf(false)
        compose.setContent {
            if (open) {
                Dialog(onDismissRequest = { open = false }) {
                    Column(Modifier.testTag("confirm-delete")) {
                        Text("Delete this doctor?")
                        Text("Cancel")
                    }
                }
            }
        }
        compose.waitForIdle()
        open = true
        compose.waitForIdle()

        val modal = SubScreenReader.identifyModal(newestWindow())
        Log.i(TAG, "dialog identified as $modal")
        assertNotNull(modal)
        assertEquals(SubScreen.Kind.MODAL, modal!!.kind)
        assertEquals("confirm-delete", modal.label)
        // Closed before returning. A window left attached outlives the rule's Activity and
        // breaks the launch of whichever test runs next, which showed up as a different
        // failure on every run rather than as this one.
        open = false
        compose.waitForIdle()
    }

    /**
     * A dropdown is a window and must still not be a screen.
     *
     * Without this filter every combo box, tooltip and overflow menu in the app mints a
     * screen — and it would look plausible in the map, which is what makes it worth a test
     * rather than a comment.
     */
    @Test
    fun a_dropdown_menu_is_not_a_modal() {
        var open by mutableStateOf(false)
        compose.setContent {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Cardiology") }, onClick = {})
            }
        }
        compose.waitForIdle()
        val before = Curtains.rootViews.size
        open = true
        compose.waitForIdle()

        assertEquals("the dropdown should have opened a window", before + 1, Curtains.rootViews.size)
        assertNull("but it is not a screen", SubScreenReader.identifyModal(newestWindow()))
        // Closed before returning. A window left attached outlives the rule's Activity and
        // breaks the launch of whichever test runs next, which showed up as a different
        // failure on every run rather than as this one.
        open = false
        compose.waitForIdle()
    }

    /**
     * The claim the whole naming strategy rests on.
     *
     * Two openings of one confirm dialog, differing only in the name of the doctor. If the
     * name were taken from the text these would be two screens, and a list of two hundred
     * doctors would be two hundred screens. The shape hash has to give them one name — and
     * has to still tell a genuinely different dialog apart, which is the second half.
     */
    @Test
    fun a_dialog_keeps_one_name_when_only_its_text_changes() {
        var body by mutableStateOf("")
        var open by mutableStateOf(false)
        compose.setContent {
            if (open) {
                Dialog(onDismissRequest = { open = false }) {
                    Column {
                        Text(body)
                        Text("Cancel")
                    }
                }
            }
        }

        fun nameWith(text: String): String {
            body = text
            open = true
            compose.waitForIdle()
            val name = SubScreenReader.identifyModal(newestWindow())?.label
            open = false
            compose.waitForIdle()
            return name!!
        }

        val silva = nameWith("Delete Dr. Silva?")
        val souza = nameWith("Delete Dr. Souza?")
        Log.i(TAG, "same dialog, different text: '$silva' vs '$souza'")

        assertTrue("no testTag or paneTitle, so it should fall back to a shape hash",
            silva.startsWith("dialog-"))
        assertEquals("per-user text must not change the screen's identity", silva, souza)
    }

    // ------------------------------------------------- in-composition overlays

    /**
     * The measurement that says an in-composition sheet cannot be detected.
     *
     * Kept as a test rather than a comment because it is a claim about the framework, and
     * if a future Compose release starts marking these properly it should be noticed and
     * `LightSessionSubScreen` should stop being necessary. An expanded `BottomSheetScaffold`
     * opens no window, and its `Collapse` marker sits on a drag handle a fraction the size
     * of the screen — the same shape of signal an expanded list row gives.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun an_in_composition_sheet_is_still_invisible_to_the_reader() {
        compose.setContent {
            val state = rememberBottomSheetScaffoldState()
            val scope = rememberCoroutineScope()
            BottomSheetScaffold(
                scaffoldState = state,
                sheetPeekHeight = 56.dp,
                sheetContent = { Text("Filter by speciality") },
            ) {
                Button(onClick = { scope.launch { state.bottomSheetState.expand() } }) {
                    Text("Open sheet")
                }
            }
        }
        compose.waitForIdle()

        val roots = Curtains.rootViews.size
        compose.onNodeWithText("Open sheet").performClick()
        compose.waitForIdle()

        assertEquals("no window is opened, so the modal path never fires", roots, Curtains.rootViews.size)
        assertNull("and there is nothing in semantics to find it by", SubScreenReader.readSubScreen(decor()))
    }

    @Test
    fun a_structurally_different_dialog_gets_a_different_name() {
        var withField by mutableStateOf(false)
        var open by mutableStateOf(false)
        compose.setContent {
            if (open) {
                Dialog(onDismissRequest = { open = false }) {
                    Column {
                        Text("Title")
                        if (withField) {
                            androidx.compose.material3.TextField(value = "", onValueChange = {})
                            Text("Extra row")
                        }
                        Text("Cancel")
                    }
                }
            }
        }

        fun name(hasField: Boolean): String {
            withField = hasField
            open = true
            compose.waitForIdle()
            val label = SubScreenReader.identifyModal(newestWindow())?.label
            open = false
            compose.waitForIdle()
            return label!!
        }

        val plain = name(false)
        val withInput = name(true)
        Log.i(TAG, "different dialogs: '$plain' vs '$withInput'")
        assertTrue(plain != withInput)
    }

    /**
     * A bottom navigation bar and a tab row in the same screen.
     *
     * `NavigationBarItem` carries `Role.Tab` exactly as `Tab` does — established in
     * `ComposeOverlayProbeTest` — so a reader that stops at the first selected one it finds
     * reports whichever the composition happens to emit first. If that is the nav bar, the
     * screen's real tabs are invisible: the value never changes as the reader switches
     * them, so nothing is ever reported.
     *
     * This is the shape pharm-manager has, and the shape that failed on it.
     */
    @Test
    fun a_bottom_bar_does_not_hide_the_screens_own_tabs() {
        compose.setContent {
            var tab by remember { mutableStateOf(0) }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = true,
                            onClick = {},
                            icon = { Text("D") },
                            label = { Text("Doctors") },
                        )
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    TabRow(selectedTabIndex = tab) {
                        listOf("All", "Pending").forEachIndexed { index, label ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val onArrival = SubScreenReader.selectedTabs(decor())
        Log.i(TAG, "selected on arrival: $onArrival")
        assertTrue("the screen's own tab has to be in there", onArrival.contains("All"))

        compose.onNodeWithText("Pending").performClick()
        compose.waitForIdle()
        val afterTap = SubScreenReader.selectedTabs(decor())
        Log.i(TAG, "selected after tapping Pending: $afterTap")
        assertTrue("switching tabs has to be visible", afterTap.contains("Pending"))
        assertFalse("and the old one gone", afterTap.contains("All"))
    }
}
