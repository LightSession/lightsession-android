package com.lightsession

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.SkeletonGenerator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the wireframe sees content that lives in a subcomposition.
 *
 * A production app reported four screens as near-empty wireframes — 98.9% background and a
 * single rect — while a fifth came out correct. Measured from the stored PNGs, not inferred:
 * the two list screens were *byte-identical* to each other, which no timing race produces.
 * The one that worked was the one screen built from a plain `Column`; every screen that
 * failed had a `Scaffold`, a `LazyColumn`, or both.
 *
 * Both of those are `SubcomposeLayout`, and a subcomposition's slot table belongs to a
 * different `Composition` than the one hanging off the root `AndroidComposeView`. So the
 * question this answers is narrow: walking from the root composition, does the scan reach
 * what a `LazyColumn` put on the screen, or does it stop at the frame around it?
 *
 * Every case renders the same twenty items, and the eager `Column` is the control that makes
 * the rest mean anything: it says what "the scan can see twenty items" is worth in rects, so
 * a low lazy count reads as *lost* rather than merely small. `Scaffold` and `LazyColumn` are
 * measured apart as well as together, so a failure names which one.
 *
 * One `setContent` for all of it, switched by state — the test rule permits only one, and
 * recomposition is also the honest reproduction: in the app these screens replace each other
 * inside a single Activity rather than each getting a fresh window.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.SubcompositionScanProbeTest
 */
@RunWith(AndroidJUnit4::class)
class SubcompositionScanProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Case { EAGER, PLAIN, LAZY, SCAFFOLD, BOTH }

    private val items = (1..20).map { "item $it" }

    /** Rect count and kind census for whatever is currently composed. */
    private fun scan(label: String): Int {
        var count = 0
        // Laid out, not merely idle. `waitForIdle` says the composition has gone quiet, which is
        // not the same as the hosting `ComposeView` having been measured — and on Compose 1.11 it
        // is not even close: after `case` changes, the view reports 0x0 with `isShown` true, so
        // `scanViewHierarchy` correctly refuses to descend and every case after the first came
        // back with the View-only wireframe. Five rects, no `TEXT` at all, on content holding
        // twenty of them. Compose 1.10.5 measured inside the idle wait and hid this entirely.
        awaitLayout()
        rule.runOnUiThread {
            val root = rule.activity.window.decorView.rootView
            val rects = SkeletonGenerator().frameFrom(root, backgroundColor = 0)?.rects.orEmpty()
            val census = rects.groupingBy { it.kind }.eachCount()
            Log.i(TAG, "$label -> ${rects.size} rects $census")
            count = rects.size
        }
        rule.waitForIdle()
        return count
    }

    /** Blocks until the composition's host view actually has a size. */
    private fun awaitLayout() {
        val view = rule.activity.window.decorView
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var sized = false
            rule.runOnUiThread { sized = composeViewIsSized(view) }
            if (sized) return
            rule.waitForIdle()
            Thread.sleep(16)
        }
        error("the composition's host view never got a size")
    }

    private fun composeViewIsSized(view: View): Boolean =
        if (view is ComposeView) {
            view.width > 0 && view.height > 0
        } else {
            view is ViewGroup && (0 until view.childCount).any { composeViewIsSized(view.getChildAt(it)) }
        }

    /** The shape that produced a usable wireframe in production: no subcomposition anywhere. */
    @Composable
    private fun PlainColumn() {
        var text by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Text("sign in")
            TextField(value = text, onValueChange = { text = it })
            TextField(value = text, onValueChange = { text = it })
            Button(onClick = {}) { Text("enter") }
        }
    }

    @Test
    fun subcompositionContentReachesTheWireframe() {
        var case by mutableStateOf(Case.EAGER)

        rule.setContent {
            when (case) {
                // Twenty items with no subcomposition at all — the control.
                Case.EAGER -> Column(Modifier.fillMaxSize()) { items.forEach { Text(it) } }
                Case.PLAIN -> PlainColumn()
                // One SubcomposeLayout, items in a child composition.
                Case.LAZY -> LazyColumn(Modifier.fillMaxSize()) { items(items) { Text(it) } }
                // Also a SubcomposeLayout, but its content is eager.
                Case.SCAFFOLD -> Scaffold(topBar = { Text("bar") }) { pad ->
                    Column(Modifier.padding(pad)) { items.forEach { Text(it) } }
                }
                // What the failing production screens actually were.
                Case.BOTH -> Scaffold(topBar = { Text("bar") }) { pad ->
                    LazyColumn(Modifier.padding(pad).fillMaxSize()) { items(items) { Text(it) } }
                }
            }
        }

        val counts = Case.entries.associateWith { c ->
            case = c
            rule.waitForIdle()
            scan(c.name)
        }

        Log.i(TAG, "SUMMARY $counts")

        val eager = counts.getValue(Case.EAGER)
        // The control has to be rich, or the probe measures nothing and every conclusion
        // below it is void.
        assertTrue(
            "eager Column of 20 produced only $eager rects; the scan is broken for reasons " +
                "that have nothing to do with subcomposition",
            eager >= 20,
        )

        // Compared against the control rather than an absolute number, so the bar moves with
        // whatever the scan legitimately reports for the same content.
        for (c in listOf(Case.LAZY, Case.SCAFFOLD, Case.BOTH)) {
            val got = counts.getValue(c)
            assertTrue(
                "$c produced $got rects against $eager for the same 20 items: the content is " +
                    "in a subcomposition the scan never reached",
                got >= eager / 2,
            )
        }
    }

    private companion object {
        const val TAG = "SubcompositionProbe"
    }
}
