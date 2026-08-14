package com.lightsession.mapper

import android.view.View
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.navigation.NavController

/**
 * Finds a Compose `NavController` the app never handed over.
 *
 * ## Why this exists
 *
 * `withNavigationTracking()` is one line in the host's composition, and an app that omits it gets
 * every destination of its `NavHost` collapsed into a single node named after the Activity — the
 * fallback [ScreenMapperIntegration.resolveComposeScreen] takes when the grace period ends with no
 * controller registered. That fallback is right when the Activity really is one screen, and wrong
 * in the way that is hardest to notice when it is not: the map looks populated, the wireframes are
 * real, and the five destinations behind them are simply invisible.
 *
 * The reason it was a manual step is true as far as it goes — Compose keeps no global registry of
 * NavControllers, so there is nothing to ask. But "no registry" is not "unreachable": a
 * `rememberNavController()` is *remembered*, which puts it in the slot table this SDK already
 * walks to find subcompositions. `NavControllerReachProbeTest` measures exactly that against a
 * `NavHost` set up the way a forgetful app sets one up, and finds `NavHostController` in the slot
 * table with its current route readable, while `Navigation.findNavController` on the decor view
 * returns null — the view-tree tag is a fragment-host convention that `NavHost` does not follow.
 *
 * So the integration step stops being load-bearing. An app that calls `withNavigationTracking()`
 * is unaffected: registration happens the moment the composition runs, long before the grace
 * period this consults, so there is nothing here left to find.
 *
 * ## What is deliberately not done
 *
 * Only *reads*. Every value comes out of a field or a property the navigation library documents;
 * nothing here invokes a method on a live runtime object to see what happens. That line was
 * learned expensively — see `CompositionContexts.elementsOf` — and it is the difference between a
 * walk that observes and one that runs the host's code inside its own UI.
 *
 * And no listener is attached from here. This only *finds*; whether to track what it found is
 * [ScreenMapperIntegration]'s decision, made through the same
 * [ScreenMapperIntegration.registerComposeNavController] the explicit path uses, so a discovered
 * controller and a handed-over one are the same thing from that point on — including the nested
 * `NavHost` shell handling that took its own commit to get right.
 */
internal object NavControllerDiscovery {

    /**
     * How deep to look before giving up.
     *
     * A `NavHost`'s controller sits near the top of the composition — it has to, since the
     * `NavHost` composable receives it as a parameter — and the probe found it within the first
     * hundred groups of a two-destination app. The cap is a guard against a pathological tree, not
     * a tuning knob: this runs once per Activity, at the end of a three-second grace period.
     */
    private const val MAX_GROUPS = 4_000

    /**
     * The controllers reachable from this view's composition, outermost first.
     *
     * Plural because a nested `NavHost` has two, and order matters to the caller: the outer one is
     * the shell, and reporting it as a screen is the bug `ScreenMapperIntegration` already guards
     * against by holding a report for two frames. Traversal is pre-order, so the outer controller
     * is found first and the caller can register them in that order — which is the order the
     * explicit path produces too, since the outer composition runs first.
     */
    @OptIn(UiToolingDataApi::class)
    fun findIn(composeView: View, treeOf: (View) -> Group?): List<NavController> {
        val tree = runCatching { treeOf(composeView) }.getOrNull() ?: return emptyList()
        val found = ArrayList<NavController>(2)
        var groups = 0

        fun walk(group: Group) {
            if (groups >= MAX_GROUPS) return
            groups++
            for (datum in group.data) {
                if (datum is NavController && found.none { it === datum }) found.add(datum)
            }
            for (child in group.children) walk(child)
        }

        runCatching { walk(tree) }
        return found
    }
}
