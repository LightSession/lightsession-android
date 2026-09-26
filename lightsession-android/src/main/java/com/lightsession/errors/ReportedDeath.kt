package com.lightsession.errors

/**
 * A crash an embedder reported, standing for the process death that follows it.
 *
 * A runtime that lives inside this process ends it over a fatal error the only way it can, with a
 * native exception: React Native turns a JavaScript error nothing caught into a
 * `JavascriptException` thrown on its native modules thread. By then the embedder has already
 * reported the error in its own terms — a `TypeError`, with the JavaScript frames — so the native
 * exception is the same crash a second time, under a wrapper whose type and frames are React
 * Native's for every JavaScript crash an app has. Measured on the React Native example: each crash
 * arrived twice, once as the `TypeError` and once as a `JavascriptException` in one group shared by
 * every JavaScript crash, the second 2 ms after the first from a handler, 15 ms from a render.
 *
 * So the report stands for the death. A native crash within [WINDOW_MS] of it is not recorded
 * again; it still goes down the handler chain, untouched, to the system and to every reporter
 * installed before this SDK.
 *
 * Bounded in time because the embedder speaks for what its runtime is about to do, and an app can
 * make that wrong: a React Native app that swapped in its own fatal-error handler to keep running
 * survives the error it reported as a crash, and a native crash long after that is a crash of its
 * own. Ten seconds is hundreds of times the gap measured and still expires long before anything
 * else is likely to go wrong.
 *
 * The clock is injected so the window can be walked by a JVM test.
 */
internal class ReportedDeath(private val clock: () -> Long) {

    @Volatile
    private var reportedAt = NEVER

    /** An embedder's crash has just been written. */
    fun record() {
        reportedAt = clock()
    }

    /** Whether a native crash now is the death an embedder already reported. */
    fun covers(): Boolean {
        val at = reportedAt
        if (at == NEVER) return false
        return clock() - at in 0..WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 10_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
