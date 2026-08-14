package com.lightsession

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.NavControllerDiscovery
import com.lightsession.mapper.NavControllerWatch
import com.lightsession.mapper.SkeletonGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a `NavHost` which composes *late* still gets its controller noticed.
 *
 * [UntrackedNavControllerTest] holds the ground floor: a remembered controller is reachable from
 * the composition. This holds the storey above it: reachable **when**. The one-shot scan runs at
 * the end of the grace period, which quietly assumed the `NavHost` exists by then — and the first
 * real app integrated keeps its `NavHost` behind a `StateFlow<Destination?>` filled by an auth
 * check. Resolve slower than the grace period and the one-shot scans a composition that honestly
 * holds no controller, names the Activity, and never looks again.
 *
 * The watch's claim is narrower than "polls until it finds": the state write that mounts the
 * `NavHost` is a snapshot apply, so the composition growing a controller *announces itself*, and
 * the watch only walks after an announcement — bounded by its own debounce. These tests assert
 * both halves at the watch's level, not through the mapper, for [UntrackedNavControllerTest]'s
 * reason: driving the whole integration would test the grace clock as much as the mechanism.
 */
@RunWith(AndroidJUnit4::class)
class NavControllerWatchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A tick that does what the mapper's tick does: discovery, identity-deduped. */
    @OptIn(androidx.compose.ui.tooling.data.UiToolingDataApi::class)
    private fun discoveringWatch(into: CopyOnWriteArrayList<NavController>): NavControllerWatch {
        val generator = SkeletonGenerator()
        return NavControllerWatch {
            val decor = rule.activity.window.decorView
            val found = NavControllerDiscovery.findIn(decor) { generator.compositionTreeOf(it) }
            into.addAll(found.filter { c -> into.none { it === c } })
        }
    }

    /** The case the one-shot cannot cover: the controller does not exist until after it looked. */
    @Test
    fun a_navhost_that_composes_late_is_still_noticed() {
        val found = CopyOnWriteArrayList<NavController>()
        val watch = discoveringWatch(found)

        val startDecided = mutableStateOf(false)
        rule.setContent {
            if (startDecided.value) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "alpha", modifier = Modifier.fillMaxSize()) {
                    composable("alpha") { Text("alpha") }
                    composable("beta") { Text("beta") }
                }
            } else {
                Text("loading", Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()

        watch.arm()
        try {
            // The auth check landing, reduced to what it is: one state write. Nothing else —
            // no touch, no navigation, no lifecycle event — tells the SDK anything happened.
            rule.runOnUiThread { startDecided.value = true }
            rule.waitUntil(timeoutMillis = 5_000) { found.isNotEmpty() }
        } finally {
            watch.cancel()
        }

        assertEquals("alpha", found.first().currentDestination?.route)

        // And being noticed did not disturb it: the screen the controller belongs to is still
        // there and still has a size. Same invariant as the discovery tests, held here because
        // the watch is what decides how *often* that read happens.
        var width = 0
        var height = 0
        rule.runOnUiThread {
            width = rule.activity.window.decorView.width
            height = rule.activity.window.decorView.height
        }
        assertTrue("the window collapsed after a watch scan: ${width}x$height", width > 0 && height > 0)
        assertNotNull(
            "the screen is gone after a watch scan",
            rule.onAllNodes(hasText("alpha")).fetchSemanticsNodes().firstOrNull(),
        )
    }

    /**
     * A write-heavy screen pays a bounded number of walks, not one per write.
     *
     * The bound is deliberately loose — debounce tests that assert exact counts assert the
     * emulator's scheduler — but it is two orders of magnitude below the thirty writes made, which
     * is the difference the debounce exists to make.
     */
    @Test
    fun a_burst_of_writes_costs_bounded_scans() {
        val ticks = AtomicInteger()
        val watch = NavControllerWatch { ticks.incrementAndGet() }

        val counter = mutableStateOf(0)
        rule.setContent { Text("n=${counter.value}", Modifier.fillMaxSize()) }
        rule.waitForIdle()

        watch.arm()
        try {
            repeat(30) { i ->
                rule.runOnUiThread { counter.value = i + 1 }
                Thread.sleep(20)
            }
            rule.waitUntil(timeoutMillis = 5_000) { ticks.get() >= 1 }
            // Let a trailing scan land before counting.
            Thread.sleep(900)
        } finally {
            watch.cancel()
        }

        val scans = ticks.get()
        assertTrue("expected a handful of scans for 30 writes, got $scans", scans in 1..6)
    }

    /** Cancelled means silent — the observer stays registered, the ticks stop. */
    @Test
    fun cancel_stops_the_ticks() {
        val ticks = AtomicInteger()
        val watch = NavControllerWatch { ticks.incrementAndGet() }

        val counter = mutableStateOf(0)
        rule.setContent { Text("n=${counter.value}", Modifier.fillMaxSize()) }
        rule.waitForIdle()

        watch.arm()
        rule.runOnUiThread { counter.value = 1 }
        rule.waitUntil(timeoutMillis = 5_000) { ticks.get() >= 1 }
        watch.cancel()

        val afterCancel = ticks.get()
        repeat(10) { i ->
            rule.runOnUiThread { counter.value = 100 + i }
            Thread.sleep(20)
        }
        Thread.sleep(900)
        assertEquals("a cancelled watch kept scanning", afterCancel, ticks.get())
    }
}
