package com.lightsession.replay

import java.util.concurrent.ThreadFactory

/**
 * Names the SDK's threads, and makes them daemons.
 *
 * Two things `Executors.defaultThreadFactory()` will not do, and both matter for code that runs
 * inside somebody else's app:
 *
 *  * **A name.** Measured on a running host: 31 threads in the process, and the SDK's show up as
 *    `LightSession-Encoder` and `LightSession-Scheduler` rather than `pool-3-thread-1`. When the app's
 *    own team opens an ANR trace or a profiler, that is the difference between "this is the analytics
 *    SDK" and a thread nobody can attribute. An SDK that cannot be identified in a profile gets
 *    blamed for whatever is near it.
 *  * **`isDaemon = true`.** The default factory creates non-daemon threads, which hold the JVM open.
 *    It shows up as a test suite that will not exit rather than as anything a user sees, but there is
 *    no reason for a recorder's executor to outlive the thing it records.
 *
 * ## What used to be here
 *
 * An `enableCounter` flag with an `AtomicInteger`, a `priority` parameter, and four companion
 * builders — `forBackground`, `forIO`, `forNetwork`, `forAnalytics`. Both call sites passed
 * `enableCounter = false`, nothing ever passed a priority, and the four builders had no callers at
 * all: two thirds of the file existed for uses that never arrived.
 *
 * Also an uncaught-exception handler that printed to `System.err` and stopped there. That was worse
 * than absent. It was the only such handler in the SDK, so it complemented nothing and instead
 * *replaced* the default on these threads — an exception in the encoder no longer reached the host
 * app's `Thread.setDefaultUncaughtExceptionHandler`, and therefore never reached their crash
 * reporting. It became a stack trace on stderr with no tag. An SDK swallowing its host's crashes is
 * worse than one that lets them through: at least a crash that propagates gets seen.
 */
internal class LightSessionThreadFactory(
    private val threadName: String,
) : ThreadFactory {

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, threadName).apply { isDaemon = true }
}
