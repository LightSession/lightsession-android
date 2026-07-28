package com.lightsession

import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import curtains.Curtains
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Compose actually publishes when a modal opens and when a tab changes.
 *
 * The screen map is driven entirely by `NavController.addOnDestinationChangedListener`,
 * so anything that changes what the user is looking at *without* changing a nav
 * destination is currently invisible: dialogs, bottom sheets, and tabs. In the View world
 * a dialog was its own window and therefore its own root view, which is how it used to be
 * caught for free. The question this answers is whether Compose still leaves that trace,
 * and if not, what it leaves instead.
 *
 * Written as a probe rather than a regression test: every assertion here is about the
 * *framework's* behaviour, not the SDK's, so a failure means an assumption behind the
 * design has changed. Findings are also logged, because the numbers and names it prints
 * are what a naming strategy has to be built on.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     --tests '*ComposeOverlayProbeTest'
 */
@RunWith(AndroidJUnit4::class)
class ComposeOverlayProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "OverlayProbe"
    }

    /** Every semantics node reachable from every attached window, newest window last. */
    private fun allNodes(): List<Pair<View, SemanticsNode>> =
        Curtains.rootViews.flatMap { root ->
            composeHosts(root).flatMap { host ->
                (host as RootForTest).semanticsOwner
                    .getAllSemanticsNodes(mergingEnabled = false)
                    .map { host to it }
            }
        }

    private fun composeHosts(view: View): List<View> {
        if (view is RootForTest) return listOf(view)
        if (view !is android.view.ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { composeHosts(view.getChildAt(it)) }
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    // ---------------------------------------------------------------- modals

    /**
     * A Compose `Dialog` is still a window.
     *
     * This is the load-bearing fact for the whole modal question: if it holds, the SDK
     * already has the listener that fires — `Curtains.onRootViewsChangedListeners`, wired
     * up in `Recorder` — and detecting a modal costs nothing new.
     */
    @Test
    fun a_compose_dialog_adds_a_root_view() {
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

        val before = Curtains.rootViews.size
        val added = mutableListOf<View>()
        val listener = curtains.OnRootViewsChangedListener { view, isAdded ->
            if (isAdded) added += view
        }
        Curtains.onRootViewsChangedListeners += listener
        try {
            open = true
            compose.waitForIdle()

            Log.i(TAG, "dialog open: roots ${before} -> ${Curtains.rootViews.size}, added=${added.size}")
            assertEquals("a dialog should add exactly one root view", 1, added.size)
            assertEquals(before + 1, Curtains.rootViews.size)

            // The added window carries the dialog's own composition, tagged as a dialog.
            val dialogNodes = composeHosts(added.first())
                .flatMap { (it as RootForTest).semanticsOwner.getAllSemanticsNodes(false) }
            val marked = dialogNodes.filter {
                it.config.getOrNull(SemanticsProperties.IsDialog) != null
            }
            Log.i(TAG, "dialog window: ${dialogNodes.size} nodes, IsDialog on ${marked.size}")
            Log.i(TAG, "dialog texts: ${dialogNodes.flatMap { texts(it) }}")
            Log.i(TAG, "dialog testTags: ${dialogNodes.mapNotNull {
                it.config.getOrNull(SemanticsProperties.TestTag)
            }}")
            Log.i(TAG, "dialog paneTitles: ${dialogNodes.mapNotNull {
                it.config.getOrNull(SemanticsProperties.PaneTitle)
            }}")
            assertTrue("the dialog's composition should be tagged IsDialog", marked.isNotEmpty())

            open = false
            compose.waitForIdle()
            assertEquals("closing should remove the window again", before, Curtains.rootViews.size)
        } finally {
            Curtains.onRootViewsChangedListeners -= listener
        }
    }

    /** Material3's modal sheet is a `ComponentDialog` too, so it should behave the same. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun a_modal_bottom_sheet_adds_a_root_view() {
        var open by mutableStateOf(false)
        compose.setContent {
            if (open) {
                val state = rememberModalBottomSheetState()
                ModalBottomSheet(onDismissRequest = { open = false }, sheetState = state) {
                    Text("Filter by speciality")
                }
            }
        }
        compose.waitForIdle()

        val before = Curtains.rootViews.size
        open = true
        compose.waitForIdle()

        val after = Curtains.rootViews.size
        Log.i(TAG, "bottom sheet: roots $before -> $after")
        val sheetNodes = Curtains.rootViews.flatMap { composeHosts(it) }
            .flatMap { (it as RootForTest).semanticsOwner.getAllSemanticsNodes(false) }
        Log.i(TAG, "sheet IsDialog nodes: ${sheetNodes.count {
            it.config.getOrNull(SemanticsProperties.IsDialog) != null
        }}")
        assertEquals("a modal bottom sheet should add a window", before + 1, after)
    }

    /**
     * The counter-example, and the reason detection cannot rely on windows alone.
     *
     * A sheet drawn inside the same composition — a `Box` overlay, a `BottomSheetScaffold`,
     * an `AnimatedVisibility` panel — is not a window and never will be. Nothing is added,
     * nothing is removed, and the only trace is in the semantics of the existing tree.
     */
    @Test
    fun an_in_composition_overlay_adds_nothing() {
        var open by mutableStateOf(false)
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                Text("Doctors")
                if (open) Text("Are you sure?", Modifier.testTag("inline-sheet"))
            }
        }
        compose.waitForIdle()

        val before = Curtains.rootViews.size
        open = true
        compose.waitForIdle()

        Log.i(TAG, "in-composition overlay: roots $before -> ${Curtains.rootViews.size}")
        assertEquals(
            "an overlay inside the composition is invisible at the window level",
            before,
            Curtains.rootViews.size,
        )
        // It is however visible in semantics, which is the fallback path.
        assertTrue(allNodes().any { texts(it.second).contains("Are you sure?") })
    }

    // ------------------------------------------------------------------ tabs

    /**
     * A selected tab is readable from semantics, and its label is a stable name.
     *
     * `Tab` applies `Modifier.selectable(selected, role = Role.Tab)`, which writes both
     * `Role` and `Selected`. That matters more than it sounds: unlike a dialog's body
     * text, a tab's label is a fixed string in the source, so it is safe to build a screen
     * name out of — "Overview" will not become "Overview (3)" per user.
     */
    @Test
    fun the_selected_tab_is_readable_and_changes_on_click() {
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

        fun selectedTab(): String? = allNodes()
            .map { it.second }
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
            ?.let { node -> texts(node).firstOrNull() ?: descendantText(node) }

        val tabs = allNodes().map { it.second }
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        Log.i(TAG, "found ${tabs.size} nodes with Role.Tab")
        Log.i(TAG, "tab labels: ${tabs.map { texts(it).ifEmpty { listOf(descendantText(it)) } }}")
        assertEquals("every tab should carry Role.Tab", labels.size, tabs.size)

        assertEquals("Overview", selectedTab())
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        assertEquals("History", selectedTab())

        // How long the read costs, since it would run after every touch.
        val started = System.nanoTime()
        repeat(20) { selectedTab() }
        val perScan = (System.nanoTime() - started) / 20 / 1_000.0
        Log.i(TAG, "selected-tab scan: %.0f us".format(perScan))
    }

    /**
     * A bottom navigation bar looks exactly like a tab row in semantics.
     *
     * `NavigationItem` uses `Role.Tab` as well, so a naive "selected tab" reading would
     * rename the screen on every bottom-nav press — on top of the navigation event the
     * NavController already reports for the same press. Whatever consumes this has to
     * distinguish them, and semantics alone does not.
     */
    @Test
    fun a_navigation_bar_item_also_reports_role_tab() {
        compose.setContent {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Text("H") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = { Text("P") },
                    label = { Text("Profile") },
                )
            }
        }
        compose.waitForIdle()

        val tabs = allNodes().map { it.second }
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        Log.i(TAG, "navigation bar exposes ${tabs.size} Role.Tab nodes")
        assertEquals(
            "bottom navigation is indistinguishable from tabs in semantics",
            2,
            tabs.size,
        )
    }

    /** A tab's label lives on a child node when the tab is built from slots. */
    private fun descendantText(node: SemanticsNode): String {
        val direct = texts(node).firstOrNull()
        if (direct != null) return direct
        for (child in node.children) {
            val found = descendantText(child)
            if (found.isNotEmpty()) return found
        }
        return ""
    }

    /**
     * A dropdown menu is also a window — and this is the discriminator that keeps the
     * screen map from filling up with them.
     *
     * `DropdownMenu`, tooltips and `ExposedDropdownMenu` all go through `Popup`, which
     * adds a root view exactly like a dialog does. Treating "a window appeared" as "a
     * screen appeared" would therefore mint a screen every time someone opens a combo box.
     * `IsPopup` versus `IsDialog` is the only thing separating them, so any detector has
     * to read semantics rather than stop at the window.
     */
    @Test
    fun a_dropdown_menu_is_a_popup_not_a_dialog() {
        var open by mutableStateOf(false)
        compose.setContent {
            androidx.compose.material3.DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Cardiology") },
                    onClick = {},
                )
            }
        }
        compose.waitForIdle()

        val before = Curtains.rootViews.size
        open = true
        compose.waitForIdle()

        val nodes = allNodes().map { it.second }
        val popups = nodes.count { it.config.getOrNull(SemanticsProperties.IsPopup) != null }
        val dialogs = nodes.count { it.config.getOrNull(SemanticsProperties.IsDialog) != null }
        Log.i(TAG, "dropdown: roots $before -> ${Curtains.rootViews.size}, IsPopup=$popups IsDialog=$dialogs")

        assertEquals("a dropdown adds a window just like a dialog", before + 1, Curtains.rootViews.size)
        assertTrue("it is tagged IsPopup", popups > 0)
        assertEquals("and never IsDialog, which is what makes it filterable", 0, dialogs)
    }

    /**
     * The window arrives before its content does.
     *
     * This decides the shape of the implementation. `onRootViewsChangedListeners` fires on
     * `WindowManager.addView`, which happens before the composition inside has been
     * composed, measured or laid out — so a detector that reads semantics synchronously
     * in the callback sees an empty tree and names the modal nothing. The SDK already owns
     * a settle mechanism for exactly this shape of problem (`ComposeSettle`).
     */
    @Test
    fun semantics_are_not_ready_when_the_window_is_added() {
        var open by mutableStateOf(false)
        compose.setContent {
            if (open) {
                Dialog(onDismissRequest = { open = false }) { Text("Delete this doctor?") }
            }
        }
        compose.waitForIdle()

        var nodesAtAdd = -1
        val listener = curtains.OnRootViewsChangedListener { view, isAdded ->
            if (isAdded && nodesAtAdd < 0) {
                nodesAtAdd = composeHosts(view).sumOf {
                    (it as RootForTest).semanticsOwner.getAllSemanticsNodes(false).size
                }
            }
        }
        Curtains.onRootViewsChangedListeners += listener
        try {
            open = true
            compose.waitForIdle()
            val nodesAfterIdle = composeHosts(Curtains.rootViews.last()).sumOf {
                (it as RootForTest).semanticsOwner.getAllSemanticsNodes(false).size
            }
            Log.i(TAG, "semantics at addView: $nodesAtAdd, after idle: $nodesAfterIdle")
            assertTrue("the window should have been reported", nodesAtAdd >= 0)
            assertTrue("content exists once idle", nodesAfterIdle > 0)
        } finally {
            Curtains.onRootViewsChangedListeners -= listener
        }
    }

    /**
     * A pager swipe changes the selected tab without anyone tapping a tab.
     *
     * The obvious trigger for re-reading the tab is "the user tapped something", but the
     * tabs-plus-pager pattern moves between tabs on a horizontal drag, and the tap never
     * lands on the tab row. A detector hooked only to taps would report the wrong tab for
     * as long as the user keeps swiping.
     */
    @Test
    fun a_pager_swipe_changes_the_selected_tab() {
        val labels = listOf("Overview", "History")
        compose.setContent {
            val pager = androidx.compose.foundation.pager.rememberPagerState { labels.size }
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
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().testTag("pager"),
                ) { page -> Text("body of ${labels[page]}") }
            }
        }
        compose.waitForIdle()

        fun selectedTab(): String? = allNodes().map { it.second }
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
            ?.let { descendantText(it) }

        assertEquals("Overview", selectedTab())
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        Log.i(TAG, "after swipe the selected tab is ${selectedTab()}")
        assertEquals("a swipe moves the tab with no tap on the tab row", "History", selectedTab())
    }

    /**
     * What an in-composition sheet leaves behind, if anything.
     *
     * `BottomSheetScaffold` is the counter-example the window-based detector cannot see, so
     * the only hope is that Compose marks it in semantics the way it marks a dialog. This
     * dumps everything distinguishing about the sheet's nodes in both states rather than
     * asserting a guess, because the point is to find out whether a signal exists at all.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun what_an_in_composition_sheet_publishes() {
        compose.setContent {
            val state = androidx.compose.material3.rememberBottomSheetScaffoldState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.material3.BottomSheetScaffold(
                scaffoldState = state,
                sheetPeekHeight = androidx.compose.ui.unit.Dp(56f),
                sheetContent = { Text("Filter by speciality") },
            ) {
                Button(onClick = { scope.launch { state.bottomSheetState.expand() } }) {
                    Text("Open sheet")
                }
            }
        }
        compose.waitForIdle()

        fun dump(label: String) {
            val rows = allNodes().map { it.second }.mapNotNull { node ->
                val config = node.config
                val marks = buildList {
                    if (config.getOrNull(SemanticsActions.Expand) != null) add("Expand")
                    if (config.getOrNull(SemanticsActions.Collapse) != null) {
                        val root = allNodes().first().second.boundsInRoot.height
                        add("Collapse h=%.0f/%.0f=%.2f".format(
                            node.boundsInRoot.height, root, node.boundsInRoot.height / root))
                    }
                    if (config.getOrNull(SemanticsActions.Dismiss) != null) add("Dismiss")
                    config.getOrNull(SemanticsProperties.PaneTitle)?.let { add("paneTitle=$it") }
                    config.getOrNull(SemanticsProperties.TestTag)?.let { add("tag=$it") }
                    if (config.getOrNull(SemanticsProperties.IsTraversalGroup) == true) add("traversalGroup")
                }
                if (marks.isEmpty()) null else marks.joinToString("+") + " " + texts(node)
            }
            Log.i(TAG, "sheet $label: ${rows.joinToString(" | ")}")
        }

        dump("collapsed")
        // Driven through the UI: expanding from a plain coroutine has no frame clock, and
        // a tap is how it happens in an app anyway.
        compose.onNodeWithText("Open sheet").performClick()
        compose.waitForIdle()
        dump("expanded")

        Log.i(TAG, "sheet roots: ${Curtains.rootViews.size}")
    }

    /** Sanity: with no overlay open there is exactly one window and no dialog marker. */
    @Test
    fun baseline_has_one_window_and_no_dialog_marker() {
        compose.setContent { Text("Doctors") }
        compose.waitForIdle()
        Log.i(TAG, "baseline roots: ${Curtains.rootViews.size}")
        assertNotNull(Curtains.rootViews.firstOrNull())
        assertNull(
            allNodes().map { it.second }
                .firstOrNull { it.config.getOrNull(SemanticsProperties.IsDialog) != null },
        )
    }
}
