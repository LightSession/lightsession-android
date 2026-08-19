package com.lightsession.mapper

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.lightsession.LightSession
import com.lightsession.masking.MaskScanner

/**
 * Reads the selected tab, and identifies a modal, out of Compose's semantics tree.
 *
 * Semantics is the right place to ask both questions, for the same reason masking uses it
 * (see [com.lightsession.masking.MaskScanner]): it is what Compose publishes for accessibility, so
 * R8 cannot strip it, whereas composable *names* come from source information that release
 * builds erase. `Tab` applies `Modifier.selectable(selected, role = Role.Tab)` and `Dialog`
 * applies `Modifier.semantics { dialog() }`, both of which survive minification.
 *
 * Everything here was measured against a real device in `ComposeOverlayProbeTest`, which is
 * where the numbers and behaviours the design leans on are established rather than assumed.
 *
 * The traversal is hand-rolled instead of using `getAllSemanticsNodes`, which sorts every
 * node in the tree and allocates a list of all of them. This runs after every touch, and
 * it only needs the first match.
 */
internal object SubScreenReader {

    /**
     * What is on top of the current destination in this window, or null.
     *
     * Only tabs, and deliberately. An overlay drawn inside the composition rather than as
     * a window — `BottomSheetScaffold`, a `Box` panel, `AnimatedVisibility` — cannot be
     * recognised from here, and the measurement that settles it is in
     * `ComposeOverlayProbeTest`: the only marker Compose leaves is the swap from
     * `SemanticsActions.Expand` to `SemanticsActions.Collapse`, and the node carrying it is
     * the sheet's 127px drag handle on a 2127px screen — six percent. An expanded accordion
     * row in a list publishes exactly the same marker at exactly the same sort of size, and
     * nothing else separates them: no `paneTitle`, no `Dismiss`, and the traversal groups
     * are already there while the sheet is shut.
     *
     * So any size or position rule that catches the sheet also turns every expanded list
     * row into a screen, on a screen full of them. Rather than guess, the SDK lets the app
     * say: see `LightSession.setSubScreen` and the `LightSessionSubScreen` composable.
     */
    fun readSubScreen(root: View): SubScreen? =
        selectedTab(root)?.let { SubScreen(SubScreen.Kind.TAB, it) }

    /**
     * Every selected tab in this window, in traversal order.
     *
     * Plural, and that is the point. A screen can hold more than one thing that reports
     * `Role.Tab`, and the commonest case is the one that broke: a bottom navigation bar has
     * it too, so a screen with `NavigationBar` plus `ScrollableTabRow` reports two. Taking
     * the first — whichever the composition emitted earliest — meant that on a bottom-nav
     * screen the reader saw the nav item, which does not change when the user switches the
     * screen's own tabs, so switching them was invisible.
     *
     * Which of these is the sub-screen is not decidable from here. Semantics does not
     * distinguish a nav bar from a tab row, and the labels do not either. What does
     * distinguish them is that the nav item is a function of the destination and so does not
     * move without a navigation — so the caller diffs this against what was selected on
     * arrival, and whatever is new is the tab the reader chose.
     */
    fun selectedTabs(root: View): List<String> {
        val labels = mutableListOf<String>()
        forEachComposeHost(root) { host ->
            collectSelectedTabs(
                (host as RootForTest).semanticsOwner.unmergedRootSemanticsNode,
                labels,
            )
        }
        return labels
    }

    private fun collectSelectedTabs(node: SemanticsNode, into: MutableList<String>) {
        val config = node.config
        if (config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            config.getOrNull(SemanticsProperties.Selected) == true
        ) {
            SubScreens.sanitize(firstText(node))?.let { into.add(it) }
            // No descent. A tab's own label is inside it, and a nested selectable would be
            // part of that tab rather than a sibling tab group.
            return
        }
        for (child in node.children) collectSelectedTabs(child, into)
    }

    /**
     * The label of the currently selected tab in this window, or null.
     *
     * A tab's label is *usually* safe to build a screen name out of — "Overview" is a fixed
     * string in the source. The original version of this comment claimed it always is, and
     * that was wrong: Twitter's home renders each community the user joined as a sibling
     * tab, so there the label is per-user data and would mint a screen per row. Form cannot
     * tell the two apart; [TabCardinality] tells them apart by arithmetic, downstream.
     */
    fun selectedTab(root: View): String? {
        forEachComposeHost(root) { host ->
            val found = findSelectedTab((host as RootForTest).semanticsOwner.unmergedRootSemanticsNode)
            if (found != null) return found
        }
        return null
    }

    private fun findSelectedTab(node: SemanticsNode): String? {
        val config = node.config
        if (config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            config.getOrNull(SemanticsProperties.Selected) == true
        ) {
            val label = SubScreens.sanitize(firstText(node))
            if (label != null) return label
        }
        for (child in node.children) {
            val found = findSelectedTab(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Identifies a window that just appeared, or null if it is not a screen.
     *
     * The filter is the whole point. Dropdown menus, tooltips and exposed-dropdown popups
     * all add a root view exactly like a dialog does — measured, 1 window becomes 2 for
     * every one of them — so treating "a window appeared" as "a screen appeared" would
     * mint a screen every time someone opens a combo box. `IsPopup` versus `IsDialog` is
     * the only thing that separates them.
     */
    fun identifyModal(root: View): SubScreen? {
        forEachComposeHost(root) { host ->
            val tree = (host as RootForTest).semanticsOwner.unmergedRootSemanticsNode
            val marker = findMarker(tree) ?: return@forEachComposeHost
            if (marker == Marker.POPUP) return null
            return SubScreen(SubScreen.Kind.MODAL, nameModal(tree))
        }
        return identifyViewModal(root)
    }

    /**
     * The same question for a window with no Compose in it.
     *
     * Everything above reads Compose semantics, so it answers `null` for a dialog built out of
     * Views — and that is most of them outside a Compose app. Measured on the React Native
     * sample: RN's `Modal` is a plain `Dialog` holding `ReactViewGroup`s, its window reached
     * `readModal` exactly as a Compose dialog does, and `identifyModal` returned null because
     * there was no `RootForTest` to find. `Alert.alert` is an AppCompat `AlertDialog` and failed
     * the same way. Neither ever became a node, on any version. The iOS SDK does not have this
     * gap because it recognises a modal by controller class, which is blind to the UI framework.
     *
     * ## Telling a dialog from a popup without semantics
     *
     * [identifyModal] warns what is at stake: dropdown menus, tooltips and exposed-dropdown
     * popups all add a root view exactly like a dialog does, and treating "a window appeared" as
     * "a screen appeared" mints a screen every time someone opens a combo box. Compose separates
     * them with `IsPopup` versus `IsDialog`; there is no such flag here, so the separator is the
     * window's own type.
     *
     * `WindowManager.LayoutParams` partitions types by range, and the boundary is named:
     * everything below `FIRST_SUB_WINDOW` is an application window, which is what a `Dialog`
     * gets. A `PopupWindow` — every dropdown, menu and tooltip — is a *sub*-window at or above
     * that boundary, and a toast or the keyboard is a system window above them again. Focus is
     * required as well, because a window that cannot take focus is not something a person is
     * looking *at*: it is chrome over what they were already looking at.
     *
     * Two conditions, both from the window rather than from its contents, and neither of them a
     * guess about a class name — which would not survive R8 anyway.
     */
    private fun identifyViewModal(root: View): SubScreen? {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return null
        if (!isDialogWindow(params.type, params.flags)) {
            Log.d("ScreenMapper", "window is not a dialog: type=${params.type} flags=${params.flags}")
            return null
        }
        Log.d("ScreenMapper", "view-world dialog: type=${params.type}")
        return SubScreen(SubScreen.Kind.MODAL, modalLabel(root.tag as? String, viewShapeHash(root)))
    }

    /**
     * Whether a window that is not the Activity's own is a dialog rather than a popup.
     *
     * Pure, and separated from the View it was read off because this is the decision with a wrong
     * answer worth writing down. [identifyModal] states the cost of getting it wrong: dropdown
     * menus, tooltips and exposed-dropdown popups all add a root view exactly like a dialog does,
     * so answering `true` too readily mints a screen every time someone opens a combo box.
     *
     * `WindowManager.LayoutParams` partitions types by range, and the boundary is named.
     * `FIRST_SUB_WINDOW` divides application windows — which is what a `Dialog` gets — from
     * sub-windows, which is what every `PopupWindow` gets; system windows such as a toast or the
     * keyboard sit above them again. Focus is required as well: a window that cannot take focus is
     * not something a person is looking *at*, it is chrome over what they were already looking at.
     */
    internal fun isDialogWindow(typeRaw: Int, flags: Int): Boolean {
        if (typeRaw >= WindowManager.LayoutParams.FIRST_SUB_WINDOW) return false
        return (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
    }

    /**
     * The developer's own name for a dialog, or a name for its shape.
     *
     * A `View.tag` is the View-world counterpart of `Modifier.testTag`: the one thing an app sets
     * on a View for its own purposes and never for display, so it cannot be a person's data. It
     * still goes through [SubScreens.sanitize], which is what refuses a "tag" that is really a
     * record — the trap [nameModal] documents at length.
     */
    internal fun modalLabel(tag: String?, shapeHash: String): String {
        SubScreens.sanitize(tag)?.let { return it }
        return "dialog-$shapeHash"
    }

    /**
     * [shapeHash] for a tree of Views: the depth and kind of every node, and nothing else.
     *
     * The same reasoning, including what is left out. No text, because a dialog named after what
     * it says becomes one screen per record. No geometry, because a text view's width *is* its
     * content — the measurement behind `a_dialog_keeps_one_name_when_only_its_text_changes`
     * applies here unchanged.
     *
     * Kind comes from framework superclasses rather than class names. Names do not survive R8,
     * and this SDK has already been caught by that once; `is TextView` holds for a design
     * system's subclass, a third-party widget and React Native's own text view alike.
     */
    private fun viewShapeHash(root: View): String = shapeHashOf(flattenShape(root))

    /** The tree reduced to what the hash is allowed to see: a kind and a depth per node. */
    private fun flattenShape(root: View): List<Pair<Int, Int>> {
        val shape = mutableListOf<Pair<Int, Int>>()
        fun visit(view: View, depth: Int) {
            shape += shapeKindOf(view) to depth
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
        }
        visit(root, 0)
        return shape
    }

    /**
     * What a View counts as, for shape only.
     *
     * Framework superclasses rather than class names: names do not survive R8, and this SDK has
     * been caught by that before. `is TextView` holds for a design system's subclass, a
     * third-party widget and React Native's own text view alike.
     */
    private fun shapeKindOf(view: View): Int = when {
        view is EditText -> KIND_EDITABLE
        view is TextView -> KIND_TEXT
        view is ImageView -> KIND_IMAGE
        view is ViewGroup -> KIND_GROUP
        else -> KIND_OTHER
    }

    internal const val KIND_EDITABLE = 1
    internal const val KIND_TEXT = 2
    internal const val KIND_IMAGE = 3
    internal const val KIND_GROUP = 4
    internal const val KIND_OTHER = 5

    /**
     * [shapeHash] for a tree of Views, over the flattened shape rather than the tree.
     *
     * Pure, so the property that matters can be tested without an Android framework: the same
     * shape always hashes the same, and a different shape does not. What is *absent* from the
     * input is the point — no text, because a dialog named after what it says becomes one screen
     * per record; no geometry, because a text view's width *is* its content, which is the
     * measurement behind `a_dialog_keeps_one_name_when_only_its_text_changes`.
     */
    internal fun shapeHashOf(shape: List<Pair<Int, Int>>): String {
        var hash = 17L
        for ((kind, depth) in shape) {
            hash = hash * 31 + kind
            hash = hash * 31 + depth
        }
        return java.lang.Long.toHexString(hash and 0xFFFFFF)
    }

    private enum class Marker { DIALOG, POPUP }

    private fun findMarker(node: SemanticsNode): Marker? {
        val config = node.config
        if (config.getOrNull(SemanticsProperties.IsDialog) != null) return Marker.DIALOG
        if (config.getOrNull(SemanticsProperties.IsPopup) != null) return Marker.POPUP
        for (child in node.children) {
            val found = findMarker(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * A name for a dialog that stays the same every time that dialog opens.
     *
     * This is the hard half of the modal problem, and the trap is the obvious answer.
     * The dialog's text is right there — measured, a confirm dialog publishes
     * `[Delete this doctor?, Cancel]` — but naming a screen after it means "Delete Dr.
     * Silva?" and "Delete Dr. Souza?" become two screens, and a list of two hundred
     * doctors becomes two hundred screens. Content cannot be the identity.
     *
     * So the fallback is the dialog's *shape* — see [shapeHash]. It produces an opaque
     * name, which is why the two developer-authored signals are tried first: `testTag`
     * is present in the measurement above, and `paneTitle` is not set by Compose itself
     * but is set by apps that care about accessibility.
     */
    private fun nameModal(tree: SemanticsNode): String {
        firstNonNull(tree) { it.config.getOrNull(SemanticsProperties.TestTag) }
            ?.let { tag -> SubScreens.sanitize(tag)?.let { return it } }
        firstNonNull(tree) { it.config.getOrNull(SemanticsProperties.PaneTitle) }
            ?.let { title -> SubScreens.sanitize(title)?.let { return it } }
        return "dialog-" + shapeHash(tree)
    }

    /**
     * A fingerprint of the dialog's structure, blind to what it says.
     *
     * Structure only: the depth and kind of every semantics node, and nothing else. The
     * first attempt included bucketed bounds, on the theory that rounding would absorb the
     * difference between one name and another. It does not, and the test that says so is
     * `a_dialog_keeps_one_name_when_only_its_text_changes` — the same dialog reading
     * "Delete Dr. Silva?" and "Delete Dr. Souza?" hashed to `4fab23` and `e3e53a`. A text
     * node's width *is* its content: different glyphs, different pixels, and a rounding
     * coarse enough to hide that is coarse enough to merge unrelated dialogs. Text that
     * wraps to a second line moves the geometry of everything below it as well.
     *
     * The cost of dropping geometry is collisions: two dialogs with the same shape — a
     * title, a message and two buttons is a common one — land on the same name and become
     * one node in the map. That is the right way to be wrong here. A collision merges two
     * screens and is visible; the alternative splits one screen into one per row of data
     * and is not. `Modifier.testTag` on the dialog is the way out, and it is checked first.
     */
    private fun shapeHash(tree: SemanticsNode): String {
        var hash = 17L
        fun visit(node: SemanticsNode, depth: Int) {
            val config = node.config
            val kind = when {
                config.getOrNull(SemanticsProperties.EditableText) != null -> 1
                config.getOrNull(SemanticsProperties.Text) != null -> 2
                else -> 3 + (config.getOrNull(SemanticsProperties.Role)?.hashCode() ?: 0)
            }
            hash = hash * 31 + kind
            hash = hash * 31 + depth
            for (child in node.children) visit(child, depth + 1)
        }
        visit(tree, 0)
        return java.lang.Long.toHexString(hash and 0xFFFFFF)
    }

    private fun firstText(node: SemanticsNode): String? {
        node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.let { return it.text }
        for (child in node.children) {
            val found = firstText(child)
            if (found != null) return found
        }
        return null
    }

    private fun <T> firstNonNull(node: SemanticsNode, pick: (SemanticsNode) -> T?): T? {
        pick(node)?.let { return it }
        for (child in node.children) {
            val found = firstNonNull(child, pick)
            if (found != null) return found
        }
        return null
    }

    /**
     * Every Compose host under this view.
     *
     * A host's children are composition nodes rather than Views, so the walk stops there —
     * but a screen can hold more than one host (Compose embedded in a View layout, or the
     * other way round), so it does not stop at the first.
     */
    private inline fun forEachComposeHost(view: View, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(view)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current is RootForTest) {
                action(current)
                continue
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) stack.addLast(current.getChildAt(index))
            }
        }
    }
}
