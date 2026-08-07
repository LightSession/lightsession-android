package com.lightsession

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.ScreenMapperIntegration
import com.lightsession.mapper.withNavigationTracking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A destination whose whole content is another NavHost must not become a screen.
 *
 * Found in production. An app had `login`, then `home/manager` — a route rendering a scaffold whose
 * own NavHost starts at `dashboard` — and both controllers were tracked, so the map grew a node for
 * the shell as well as for the screen inside it. The numbers said what it was: 20 interactions on
 * `dashboard` against **0** on `home/manager`, across every session ever recorded. Not a
 * little-used screen. A screen where there is nothing to do, whose stored wireframe was `dashboard`
 * caught before its data arrived — which is why it read as a broken copy of it.
 *
 * Two things have to hold, and the second is the one that is easy to get wrong: the shell is not
 * reported, *and* the edge closes over the gap. `login -> dashboard`, not `login -> home/manager`
 * and not `home/manager -> dashboard`. A suppression that leaves the edge dangling moves the bug
 * rather than fixing it.
 *
 * Recording is switched off for the duration, which keeps this to the decision: everything past it
 * needs a live `CacheManager` from the singleton's one-shot `init`, and the decision is where the
 * logic being tested lives.
 *
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.NestedNavHostShellTest
 */
@RunWith(AndroidJUnit4::class)
class NestedNavHostShellTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var recordingWas = true

    @Before
    fun quietTheSenders() {
        recordingWas = Recording.enabled
        Recording.enabled = false
    }

    @After
    fun restore() {
        Recording.enabled = recordingWas
    }

    private val mapper get() = ScreenMapperIntegration.getInstance()

    @Test
    fun aDestinationHostingANestedNavHostIsNotReported() {
        var showManager by mutableStateOf(false)

        rule.setContent {
            val outer = rememberNavController().withNavigationTracking()
            NavHost(navController = outer, startDestination = "login") {
                composable("login") { Text("login") }
                // The shell: its entire content is another NavHost, exactly as `ManagerScaffold`
                // is in the app this came from.
                composable("home/manager") {
                    val inner = rememberNavController().withNavigationTracking()
                    NavHost(navController = inner, startDestination = "dashboard") {
                        composable("dashboard") { Text("dashboard") }
                        composable("team") { Text("team") }
                    }
                }
            }
            if (showManager) {
                LaunchedNavigate(outer, "home/manager")
            }
        }
        rule.waitForIdle()

        // `login` is a plain destination and must survive the grace period untouched — if it did
        // not, the delay would be eating real screens and the assertions below would pass for the
        // wrong reason.
        //
        // Only the destination is asserted, not the pair. The mapper is a process-wide singleton and
        // the suite shares it, so whatever ran before this leaves `lastScreen` set and becomes the
        // `from` here — asserting `null to "login"` passed alone and failed in the suite, which is a
        // test asserting its own isolation rather than the behaviour.
        awaitUntil("'login' reported") { mapper.lastDecidedEdge?.second == "login" }
        assertFalse(
            "'login' hosts no NavHost and must not be classed as a shell",
            "login" in mapper.knownShellRoutes,
        )

        showManager = true
        rule.waitForIdle()
        // The grace period is counted in real `Choreographer` frames, so it is waited out rather
        // than advanced. Polled instead of slept for a fixed span: a fixed sleep long enough for a
        // loaded emulator is dead time on every future run.
        awaitUntil("'home/manager' classed as a shell") {
            "home/manager" in mapper.knownShellRoutes
        }

        Log.i(
            TAG,
            "shells=${mapper.knownShellRoutes} lastEdge=${mapper.lastDecidedEdge}",
        )

        assertTrue(
            "'home/manager' hosts a nested NavHost and should have been classed as a shell; " +
                "shells were ${mapper.knownShellRoutes}",
            "home/manager" in mapper.knownShellRoutes,
        )
        assertEquals(
            "the edge has to close over the dropped shell",
            "login" to "dashboard",
            mapper.lastDecidedEdge,
        )
    }

    /**
     * Waits for [condition], failing with [what] rather than timing out anonymously.
     *
     * The window is generous on purpose: it bounds a hang, it does not assert a latency. What the
     * grace period costs is two frames, and measuring that is not this test's job.
     */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; shells=${mapper.knownShellRoutes}")
    }

    private companion object {
        const val TAG = "NestedNavHostShell"
    }
}

/** Navigates once, from a composition, without racing the initial one. */
@androidx.compose.runtime.Composable
private fun LaunchedNavigate(controller: androidx.navigation.NavController, route: String) {
    androidx.compose.runtime.LaunchedEffect(route) { controller.navigate(route) }
}
