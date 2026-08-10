package com.lightsession

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.LateContent
import com.lightsession.mapper.SkeletonGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether data arriving on a loading screen can be caught without a clock.
 *
 * The fixture is the production `Métricas` screen in miniature: a `Scaffold` whose body is a
 * spinner until a flag flips, then cards. The flag flipping *is* the data arriving — in the app it
 * is `isLoading = false` reaching `collectAsStateWithLifecycle`, which is a `MutableState` write,
 * which is a snapshot apply. No touch is involved, which is the point: the settle detector already
 * declared this screen quiet at 139 ms (measured, spinner animating invisibly on the RenderThread),
 * so the *only* remaining signal that the screen became itself is that apply.
 *
 * The waits in these tests are test machinery — a latch on a callback — not the mechanism under
 * test. The mechanism has no timeout anywhere, which is why the negative case asserts a *bounded*
 * silence rather than forever.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.LateContentTest
 */
@RunWith(AndroidJUnit4::class)
class LateContentTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "LateContentTest"
    }

    private var loading by mutableStateOf(true)

    private fun content() {
        rule.setContent {
            Scaffold(topBar = { Text("Minhas métricas") }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        Column {
                            repeat(6) { index ->
                                Card(Modifier.padding(8.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Cobertura $index")
                                        Text("72,5%")
                                        Text("29 de 40 médicos visitados")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scan(label: String): Int {
        var count = 0
        rule.runOnUiThread {
            val root = rule.activity.window.decorView.rootView
            count = SkeletonGenerator().frameFrom(root, backgroundColor = 0)?.rects?.size ?: 0
        }
        rule.waitForIdle()
        Log.i(TAG, "$label -> $count rects")
        return count
    }

    /** The whole claim: data lands, the watch fires, the rescan sees what the first scan missed. */
    @Test
    fun dataArrivingWithoutATouchYieldsARicherScan() {
        content()
        val before = scan("loading")

        val fired = CountDownLatch(1)
        val watch = LateContent()
        watch.arm { fired.countDown() }

        rule.runOnUiThread { loading = false }

        assertTrue(
            "the data arrived as a state apply and the watch never woke",
            fired.await(5, TimeUnit.SECONDS),
        )
        rule.waitForIdle()
        val after = scan("loaded")

        assertTrue(
            "the rescan saw $after rects against $before before the data — nothing was gained, " +
                "so the recapture would have resent the spinner",
            after > before,
        )
        watch.cancel()
    }

    /** A touch disarms through this. State applied after it must not fire the watch. */
    @Test
    fun cancelBeforeTheApplyMeansNoFiring() {
        content()
        val fired = CountDownLatch(1)
        val watch = LateContent()
        watch.arm { fired.countDown() }
        watch.cancel()

        rule.runOnUiThread { loading = false }
        rule.waitForIdle()

        assertFalse(
            "cancelled, and the apply fired it anyway",
            fired.await(1_500, TimeUnit.MILLISECONDS),
        )
    }

    /** One arm, one firing — the budget in the mapper is what grants second looks, not this. */
    @Test
    fun anArmFiresExactlyOnce() {
        content()
        val firings = AtomicInteger(0)
        val watch = LateContent()
        watch.arm { firings.incrementAndGet() }

        rule.runOnUiThread { loading = false }
        rule.waitForIdle()
        rule.runOnUiThread { loading = true }
        rule.waitForIdle()
        rule.runOnUiThread { loading = false }
        rule.waitForIdle()

        assertEquals(
            "three applies, and an arm that should be one-shot fired ${firings.get()} time(s)",
            1,
            firings.get(),
        )
        watch.cancel()
    }
}
