package com.lightsession

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.lightsession.session.Identity
import com.lightsession.session.Recording
import com.lightsession.session.SessionDataManager

/**
 * That stopping recording actually stops data reaching the pipeline.
 *
 * Instrumented rather than JVM because the thing worth asserting is that nothing gets *buffered*,
 * and the buffer belongs to a [SessionDataManager], which needs a real `Context` for its spool
 * directory. A test of the flag alone would assert that a boolean holds a value.
 *
 * The counter is `batchesSpooled`, which moves when data is *accepted* into a batch. The obvious
 * choice — `framesSent` — counts deliveries, and the first version of this test used it and read
 * zero in every case, because these endpoints are deliberately unreachable. `processBatch` returns
 * early when every buffer is empty and only then increments the batch number, so a flush that
 * spools nothing is exactly the observable this needs.
 */
@RunWith(AndroidJUnit4::class)
class RecordingGateTest {

    private companion object {
        const val TAG = "RecordingGate"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun manager(): SessionDataManager =
        SessionDataManager(context, LightSessionConfig(
                apiKey = "test-key",
                // Unreachable on purpose. Nothing here waits for a response — the assertions
                // read the pipeline's own counters, which move when data is accepted, not when
                // it is delivered.
                ingestUrl = "http://127.0.0.1:1",
                apiUrl = "http://127.0.0.1:1",
            )).also {
            it.init(Identity.from(context))
        }

    @After
    fun tearDown() {
        Recording.enabled = true
    }

    /** Batches spooled so far. Moves when a flush had something to spool. */
    private fun spooled(manager: SessionDataManager): Long =
        (manager.getStats()["batchesSpooled"] as? Number)?.toLong() ?: 0L

    /** Feeds one of each kind, then flushes, so acceptance is visible rather than pending. */
    private fun feedAndFlush(manager: SessionDataManager) {
        feedOneOfEach(manager)
        manager.forceFlush("test")
    }

    private fun feedOneOfEach(manager: SessionDataManager) {
        manager.addFrame(byteArrayOf(1, 2, 3), isRepeatedFrame = false, currentScreen = "login")
        manager.addNavigation("login", "home", "COMPOSE")
        manager.addInteractionFromJson(
            """{"type":"TAP","points":[{"x":0.5,"y":0.5,"timestamp":1}],
               "start_time":1,"end_time":2}""",
        )
    }

    @Test
    fun with_recording_on_each_kind_is_taken() {
        // The half that makes the other half mean something: a gate that rejected everything
        // would pass a test that only checked for rejection.
        val manager = manager()
        Recording.enabled = true

        val before = spooled(manager)
        feedAndFlush(manager)
        val after = spooled(manager)

        Log.i(TAG, "recording on: spooled $before -> $after")
        assertNotEquals("nothing was spooled while recording was on", before, after)
    }

    @Test
    fun with_recording_off_nothing_is_taken() {
        val manager = manager()
        Recording.enabled = false

        val before = spooled(manager)
        feedAndFlush(manager)
        val after = spooled(manager)

        Log.i(TAG, "recording off: spooled $before -> $after")
        assertEquals("something was spooled while recording was off", before, after)
    }

    @Test
    fun the_flag_is_read_per_call_and_not_captured_at_construction() {
        // A manager built while recording was off has to start taking data the moment it is
        // turned on. Caching the flag anywhere — a constructor parameter, a field set at init —
        // would mean an app that calls `startRecording` later records nothing, and the symptom
        // is an empty session rather than an error.
        val manager = manager()

        Recording.enabled = false
        val whileOff = spooled(manager)
        feedAndFlush(manager)
        assertEquals(whileOff, spooled(manager))

        Recording.enabled = true
        feedAndFlush(manager)
        val whileOn = spooled(manager)

        Log.i(TAG, "off then on: spooled $whileOff -> $whileOn")
        assertTrue("nothing was taken after turning recording on", whileOn > whileOff)
    }
}
