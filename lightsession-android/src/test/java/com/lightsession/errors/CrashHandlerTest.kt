package com.lightsession.errors

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two invariants a crash handler lives or dies by, tested where they can actually be tested.
 *
 * These paths run exactly once per process, while it dies. A bug in them does not fail a test in
 * CI or a smoke test on a couch — it eats a customer's crash, or worse, eats the crash dialog of
 * a customer's *app*. `LightSessionThreadFactory` records the SDK's history with exactly this
 * mistake: a handler that stopped the chain and silently unplugged the host's own crash
 * reporting. That is the regression these tests pin.
 */
class CrashHandlerTest {

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var calls = 0
        var lastThread: Thread? = null
        var lastThrowable: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls++
            lastThread = t
            lastThrowable = e
        }
    }

    /** The host's handler sees the crash the app actually had — same object, untouched. */
    @Test
    fun `the previous handler receives the original throwable`() {
        val previous = RecordingHandler()
        val crash = RuntimeException("the app's crash")
        val handler = CrashHandler(previous, AtomicBoolean(false)) { _, _ -> }

        handler.uncaughtException(Thread.currentThread(), crash)

        assertEquals(1, previous.calls)
        assertSame(crash, previous.lastThrowable)
        assertSame(Thread.currentThread(), previous.lastThread)
    }

    /** Capture blowing up must not cost the host its crash dialog and its own reporters. */
    @Test
    fun `a capture that throws still forwards`() {
        val previous = RecordingHandler()
        val crash = RuntimeException("the app's crash")
        val handler = CrashHandler(previous, AtomicBoolean(false)) { _, _ ->
            throw IllegalStateException("bug in error capture")
        }

        handler.uncaughtException(Thread.currentThread(), crash)

        assertEquals(1, previous.calls)
        assertSame("the wrapper leaked instead of the crash", crash, previous.lastThrowable)
    }

    /**
     * Two threads crashing: one capture, two forwards. The second thread's crash still reaches
     * the system — it is the same process dying, and the chain is not ours to cut — but writing
     * a second report for it would race the first on the way down.
     */
    @Test
    fun `capture runs once but every crash forwards`() {
        val previous = RecordingHandler()
        var captures = 0
        val handler = CrashHandler(previous, AtomicBoolean(false)) { _, _ -> captures++ }

        handler.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        handler.uncaughtException(Thread.currentThread(), RuntimeException("second"))

        assertEquals(1, captures)
        assertEquals(2, previous.calls)
    }

    /** An app with no previous handler: nothing to chain to must not become an NPE of ours. */
    @Test
    fun `no previous handler is fine`() {
        var captured = false
        val handler = CrashHandler(null, AtomicBoolean(false)) { _, _ -> captured = true }

        handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        assertTrue(captured)
    }
}
