package com.lightsession

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.errors.ErrorCapture
import com.lightsession.errors.ErrorCrumb
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a fatal error is on disk when `addError` returns — not scheduled, not queued: written.
 *
 * This is the one claim in error capture that cannot be tested on the JVM against a fake, because
 * the claim is about a real filesystem under a real `Context`: the crash handler returns, the
 * process dies, and the only thing that decides whether the crash exists is what `renameTo`
 * published before that. Everything async in the SDK is dead the moment the handler returns —
 * every thread is a daemon — so "the write happened synchronously" is the entire feature.
 *
 * Endpoints are deliberately unreachable, same as `RecordingGateTest`: nothing here is about
 * delivery. Delivery is the next launch's `drainSpool`, which `BatchSpoolTest` already covers.
 */
@RunWith(AndroidJUnit4::class)
class CrashSpoolTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val crumbsDir = File(context.filesDir, "lightsession/spool/crumbs")
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // The spool survives across runs by design, so the previous run's batches would satisfy
        // this run's assertions. Wiped, same honesty as WireframeRatchetTest's cache wipe.
        crumbsDir.deleteRecursively()
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        // `ErrorCapture.install` swaps the process-wide default handler; leaving ours installed
        // would chain every later test's crash through this test's wiring.
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
    }

    private fun manager(): SessionDataManager =
        SessionDataManager(
            context,
            LightSessionConfig(
                apiKey = "test-key",
                ingestUrl = "http://127.0.0.1:1",
                apiUrl = "http://127.0.0.1:1",
            ),
        ).also { it.init(Identity.from(context)) }

    private fun spooledBatches(): List<String> =
        crumbsDir.listFiles()?.filter { it.isFile }?.map { it.readText() } ?: emptyList()

    @Test
    fun a_fatal_error_is_on_disk_when_addError_returns() {
        val m = manager()
        val details = ErrorCrumb.build(
            RuntimeException("the crash"),
            handled = false,
            thread = Thread.currentThread(),
            appPackage = context.packageName,
        )

        m.addError(
            details = details,
            screen = "checkout",
            screenId = "checkout_1.0_1_1080_2400_Light",
            attributes = mapOf("gateway" to "stripe"),
            fatal = true,
        )
        // No waiting, no flushing: what is on disk at this exact moment is what a crash keeps.

        val batches = spooledBatches()
        assertTrue("no crumb batch was spooled synchronously", batches.isNotEmpty())
        val batch = batches.single()
        assertTrue("the batch does not carry the error crumb", batch.contains("\"type\":\"error\""))
        assertTrue("the screen is not attached", batch.contains("checkout_1.0_1_1080_2400_Light"))
        assertTrue("the exception type is missing", batch.contains("java.lang.RuntimeException"))
        assertTrue("the message is missing", batch.contains("the crash"))
        assertTrue("the attributes are missing", batch.contains("stripe"))
        assertTrue("handled must be false on a crash", batch.contains("\"handled\":false"))
    }

    /**
     * The same claim through the real entry point: `ErrorCapture.capture` reading the live
     * (uninitialised, hence null) screen mapper and feeding the real manager. What the JVM
     * tests cannot see is precisely this wiring.
     */
    @Test
    fun capture_through_the_facade_spools_synchronously() {
        val m = manager()
        ErrorCapture.install(m, context.packageName)

        ErrorCapture.capture(
            IllegalStateException("through the facade"),
            handled = false,
            thread = Thread.currentThread(),
        )

        val batches = spooledBatches()
        assertTrue("nothing spooled through the facade", batches.isNotEmpty())
        assertTrue(
            batches.any { it.contains("through the facade") && it.contains("\"type\":\"error\"") },
        )
    }
}
