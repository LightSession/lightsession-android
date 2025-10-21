package com.lightsession.replay

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

/**
 * Handler for posting tasks to the main UI thread in LightSession.
 *
 * Provides convenient methods for executing code on the main thread,
 * with support for delayed execution and safe posting.
 *
 * @property looper the Looper to use (defaults to main looper)
 */
internal class MainLooperHandler(
    private val looper: Looper = Looper.getMainLooper()
) {
    private val handler = Handler(looper)

    /**
     * Posts a runnable to be executed on the main thread
     */
    fun post(runnable: Runnable): Boolean = handler.post(runnable)

    /**
     * Posts a runnable to be executed on the main thread with Kotlin lambda support
     */
    inline fun post(crossinline action: () -> Unit): Boolean =
        handler.post { action() }

    /**
     * Posts a runnable to be executed after the specified delay
     */
    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean =
        handler.postDelayed(runnable, delayMillis)

    /**
     * Posts a runnable to be executed after the specified delay with Kotlin lambda support
     */
    inline fun postDelayed(delayMillis: Long, crossinline action: () -> Unit): Boolean =
        handler.postDelayed({ action() }, delayMillis)

    /**
     * Posts a runnable to be executed after the specified delay with TimeUnit support
     */
    fun postDelayed(runnable: Runnable, delay: Long, unit: TimeUnit): Boolean =
        handler.postDelayed(runnable, unit.toMillis(delay))

    /**
     * Removes all pending posts of the specified runnable
     */
    fun removeCallbacks(runnable: Runnable) = handler.removeCallbacks(runnable)

    /**
     * Removes all callbacks and messages
     */
    fun removeCallbacksAndMessages() = handler.removeCallbacksAndMessages(null)

    /**
     * Checks if the current thread is the main thread
     */
    fun isMainThread(): Boolean = looper.thread == Thread.currentThread()

    /**
     * Executes the action immediately if on main thread, otherwise posts it
     */
    inline fun runOnMainThread(crossinline action: () -> Unit) {
        if (isMainThread()) {
            action()
        } else {
            post { action() }
        }
    }

    companion object {
        /**
         * Shared instance for common usage
         */
        val shared: MainLooperHandler by lazy { MainLooperHandler() }

        /**
         * Quick check if current thread is main thread
         */
        fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
    }
}