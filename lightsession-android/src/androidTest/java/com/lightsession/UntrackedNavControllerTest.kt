package com.lightsession

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.NavControllerDiscovery
import com.lightsession.mapper.SkeletonGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That an app which never handed its `NavController` over still gets its destinations named.
 *
 * The failure this prevents is quiet, which is why it earns a test rather than a comment: with no
 * controller registered, the mapper falls back to the Activity's own name after its grace period,
 * and an app with five destinations gets one node called `MainActivity` — populated, plausible,
 * and wrong. It reached a real dashboard that way, and the SDK's only defence was a line of advice
 * in logcat that nobody reads.
 *
 * Discovery is asserted at its own level rather than through the mapper. Driving the whole
 * integration would mean waiting out a three-second grace period and reading the screen map back,
 * which tests the clock as much as the lookup; what has to hold is narrower and permanent — that a
 * remembered controller is reachable from the composition, and that reading it does not disturb
 * the composition it came from.
 */
@RunWith(AndroidJUnit4::class)
class UntrackedNavControllerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(androidx.compose.ui.tooling.data.UiToolingDataApi::class)
    private fun discover(): List<NavController> {
        var found: List<NavController> = emptyList()
        val generator = SkeletonGenerator()
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            found = NavControllerDiscovery.findIn(decor) { generator.compositionTreeOf(it) }
        }
        rule.waitForIdle()
        return found
    }

    /** The case that reached production: a `NavHost` whose controller was never handed over. */
    @Test
    fun a_controller_never_handed_over_is_still_found() {
        rule.setContent {
            // No `.withNavigationTracking()`. That absence is the test.
            val nav = rememberNavController()
            NavHost(nav, startDestination = "alpha", modifier = Modifier.fillMaxSize()) {
                composable("alpha") { Text("alpha") }
                composable("beta") { Text("beta") }
            }
        }
        rule.waitForIdle()

        val found = discover()
        assertTrue("no NavController was found in the composition", found.isNotEmpty())
        assertEquals("alpha", found.first().currentDestination?.route)
    }

    /**
     * A screen with no navigation at all must find nothing.
     *
     * The half that keeps the fallback honest: if this returned something, an Activity that really
     * is one screen would stop being recorded under its own name — trading one silent wrong map
     * for another.
     */
    @Test
    fun a_composition_without_navigation_finds_nothing() {
        rule.setContent { Text("just a screen", Modifier.fillMaxSize()) }
        rule.waitForIdle()

        assertTrue("found a NavController where there is none", discover().isEmpty())
    }

    /**
     * Reading the composition must not change it.
     *
     * Stated because the last reflective walk added to this SDK measured a host's `ComposeView` at
     * 0×0 permanently — it invoked a method on a live composer while looking for a collection.
     * Discovery only reads fields, and this asserts the screen survives being read.
     */
    @Test
    fun discovery_leaves_the_composition_alone() {
        rule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "alpha", modifier = Modifier.fillMaxSize()) {
                composable("alpha") { Text("alpha") }
            }
        }
        rule.waitForIdle()

        repeat(3) { discover() }
        rule.waitForIdle()

        var width = 0
        var height = 0
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            width = decor.width
            height = decor.height
        }
        assertTrue("the window collapsed after discovery: ${width}x$height", width > 0 && height > 0)
        rule.onNodeWithTextExists("alpha")
    }

    /** `onNodeWithText` without pulling the finders API into the file's imports. */
    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithTextExists(
        text: String,
    ) {
        assertNotNull(
            "the screen is gone after discovery",
            onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().firstOrNull(),
        )
    }
}
