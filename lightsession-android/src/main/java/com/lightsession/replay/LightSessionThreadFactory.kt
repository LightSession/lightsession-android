package com.lightsession.replay

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread factory for LightSession executors.
 *
 * Creates daemon threads with customizable naming and optional thread counting.
 * All threads created by this factory are daemon threads and won't prevent JVM shutdown.
 *
 * @property threadBaseName the base name for threads (will be suffixed with counter if enabled)
 * @property enableCounter whether to append an incremental counter to thread names
 * @property priority the thread priority (default: Thread.NORM_PRIORITY)
 */
class LightSessionThreadFactory(
    private val threadBaseName: String,
    private val enableCounter: Boolean = true,
    private val priority: Int = Thread.NORM_PRIORITY
) : ThreadFactory {

    private val threadCounter = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread {
        val threadName = if (enableCounter) {
            "$threadBaseName-${threadCounter.incrementAndGet()}"
        } else {
            threadBaseName
        }

        return Thread(runnable, threadName).apply {
            isDaemon = true
            this.priority = this@LightSessionThreadFactory.priority

            // Set uncaught exception handler for better debugging
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, exception ->
                System.err.println("Uncaught exception in LightSession thread '${thread.name}': $exception")
                exception.printStackTrace()
            }
        }
    }

    companion object {
        /**
         * Creates a factory for background processing threads
         */
        fun forBackground(): LightSessionThreadFactory =
            LightSessionThreadFactory("LightSession-Background", priority = Thread.MIN_PRIORITY)

        /**
         * Creates a factory for I/O operation threads
         */
        fun forIO(): LightSessionThreadFactory =
            LightSessionThreadFactory("LightSession-IO")

        /**
         * Creates a factory for network operation threads
         */
        fun forNetwork(): LightSessionThreadFactory =
            LightSessionThreadFactory("LightSession-Network")

        /**
         * Creates a factory for analytics threads
         */
        fun forAnalytics(): LightSessionThreadFactory =
            LightSessionThreadFactory("LightSession-Analytics", priority = Thread.MIN_PRIORITY)
    }
}