package com.lightsession

import android.util.Log
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a `NavController` the app never handed over is findable from here.
 *
 * This is the question behind the one integration step the SDK cannot do for the app. Compose
 * keeps no global registry of NavControllers, so `withNavigationTracking()` exists to have the
 * host pass its controller in — and an app that forgets it gets every destination collapsed into
 * one node named after the Activity. That has been rediscovered from the consumer side more than
 * once, which is the shape of a design that depends on a manual step nothing enforces.
 *
 * But "no global registry" is not the same as "unreachable". A `rememberNavController()` is
 * remembered, so it lives in the slot table the skeleton scan already walks; and androidx has a
 * view-tree tag that fragment-based hosts set. This probe asks both, against a `NavHost` set up
 * exactly the way a forgetful app sets one up, and prints what it finds rather than asserting —
 * whether auto-detection is worth building depends on the answer.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.NavControllerReachProbeTest
 * and read the `NavReach` lines in logcat.
 */
@RunWith(AndroidJUnit4::class)
class NavControllerReachProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "NavReach"
    }

    @Test
    @OptIn(UiToolingDataApi::class)
    fun printHowANavControllerCanBeReached() {
        // No `.withNavigationTracking()`. The whole point is the app that forgot it.
        rule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                composable("home") { Text("home") }
                composable("details") { Text("details") }
            }
        }
        rule.waitForIdle()

        rule.runOnUiThread {
            val decor = rule.activity.window.decorView

            // Route 1: the view-tree tag. `NavHostFragment` sets it; whether
            // `androidx.navigation.compose.NavHost` does is the question.
            val viaViewTree = runCatching { Navigation.findNavController(decor) }.getOrNull()
            Log.i(TAG, "viaViewTree=${viaViewTree?.javaClass?.name}")

            // Route 2: the slot table, which the skeleton scan already walks for other reasons.
            val composeView = findComposeView(decor)
            val composer = composeView?.keyedTags()?.findComposition()?.unwrap()?.composer()
            val tree = composer?.compositionData?.asTree()
            if (tree == null) {
                Log.i(TAG, "NO TREE")
                return@runOnUiThread
            }

            var groups = 0
            var hits = 0
            walk(tree) { group ->
                groups++
                for (datum in group.data) {
                    if (datum is NavController) {
                        hits++
                        Log.i(
                            TAG,
                            "IN SLOT TABLE: ${datum.javaClass.name} in group '${group.name}' " +
                                "route=${datum.currentDestination?.route}",
                        )
                    }
                }
            }
            Log.i(TAG, "SUMMARY groups=$groups navControllersInSlotTable=$hits")
        }
        rule.waitForIdle()
    }

    @OptIn(UiToolingDataApi::class)
    private fun walk(group: Group, visit: (Group) -> Unit) {
        visit(group)
        group.children.forEach { walk(it, visit) }
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name.endsWith("AndroidComposeView")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findComposeView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun View.keyedTags(): SparseArray<*>? = runCatching {
        View::class.java.getDeclaredField("mKeyedTags").also { it.isAccessible = true }
            .get(this) as? SparseArray<*>
    }.getOrNull()

    private fun SparseArray<*>.findComposition(): Composition? {
        for (i in 0 until size()) (valueAt(i) as? Composition)?.let { return it }
        return null
    }

    private fun Composition.unwrap(): Composition {
        var current: Composition = this
        repeat(10) {
            val inner = current.javaClass.declaredFields.asSequence()
                .mapNotNull { f ->
                    runCatching { f.isAccessible = true; f.get(current) as? Composition }.getOrNull()
                }
                .firstOrNull { it !== current } ?: return current
            current = inner
        }
        return current
    }

    private fun Composition.composer(): Composer? = javaClass.declaredFields.asSequence()
        .mapNotNull { f ->
            runCatching { f.isAccessible = true; f.get(this) as? Composer }.getOrNull()
        }
        .firstOrNull()
}
