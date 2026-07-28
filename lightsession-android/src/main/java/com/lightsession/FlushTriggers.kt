package com.lightsession

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Flushes the session on the events that actually end a session.
 *
 * These callbacks existed before this file did — `ReplayIntegration.onTerminate()`,
 * `onLowMemory()` and `SessionDataManager.onDestroy()` were all written — and
 * *nothing called any of them*. A search across the SDK and the sample app found
 * zero call sites. So the only flush that ever ran was the five-second ticker,
 * which means closing the app discarded up to five seconds of frames and
 * interactions: exactly the moments before someone gives up on a screen, which is
 * what a session replay is for.
 *
 * Registering is the whole fix. Each trigger is one line; the reason each one is
 * here is the part worth writing down.
 */
internal class FlushTriggers(
    private val sessionDataManager: SessionDataManager
) : DefaultLifecycleObserver, ComponentCallbacks2 {

    private companion object {
        private const val TAG = "LightSession.Flush"
    }

    /**
     * Attaches to the process, not to an Activity.
     *
     * `ActivityLifecycleCallbacks` fires on every rotation and on every hop between
     * Activities inside the app, so flushing there would flush constantly and still
     * miss the case that matters. `ProcessLifecycleOwner` fires `onStop` once, when
     * the *app* stops being visible — which is when the great majority of sessions
     * actually end, and the last point at which the process is reliably alive.
     */
    fun register(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        application.registerComponentCallbacks(this)
        Log.d(TAG, "flush triggers registered: background, low memory, trim memory")
    }

    fun unregister(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        application.unregisterComponentCallbacks(this)
    }

    /**
     * The app went to background.
     *
     * The important one. After this the process can be killed at any moment without
     * further notice — Android does not promise `onDestroy`, and it certainly does
     * not promise it after a swipe-away — so this is the last guaranteed chance to
     * get the buffered data onto disk.
     */
    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "app backgrounded; flushing")
        sessionDataManager.forceFlush("background")
        // Stamped *after* the flush, so the timestamp marks when the app actually
        // stopped producing data rather than when the callback happened to run.
        sessionDataManager.markBackgrounded()
    }

    /**
     * The app came back.
     *
     * Not a flush: an upload attempt. Whatever failed to send while the app was away
     * — or while it was offline, or in a previous process — is still on disk, and
     * coming to the foreground is both a good moment to retry and usually a moment
     * with connectivity.
     */
    override fun onStart(owner: LifecycleOwner) {
        // Rotation first. It flushes anything still buffered under the old session
        // id before minting a new one, and `retryPending` would otherwise upload
        // that flush's batch as part of whichever session happened to be current.
        sessionDataManager.rotateIfIdle()
        sessionDataManager.retryPending()
    }

    /**
     * Memory pressure.
     *
     * Flushing here is not politeness, it is self-preservation: the buffers hold
     * JPEG byte arrays, so they are a meaningful share of what the system is asking
     * for. Writing them to disk hands the memory back, and a host app killed for
     * the SDK's memory use is blamed on the host app.
     */
    // The TRIM_MEMORY_* levels are deprecated from API 34, but the callback is
    // still delivered and minSdk here is 26 — so the threshold has to keep working
    // on the devices that still report the older levels.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // `TRIM_MEMORY_UI_HIDDEN` is 20, so it clears every threshold below it —
        // but it is not memory pressure, it is "your UI is no longer visible", and
        // it fires on every single backgrounding. Handling it here meant
        // backgrounding produced two flushes and labelled the first one
        // `trim_memory`, which is a lie in the batch metadata. [onStop] already
        // covers that case and labels it correctly.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.d(TAG, "memory pressure (level $level); flushing")
            sessionDataManager.forceFlush("trim_memory")
        }
    }

    @Deprecated("Kept because ComponentCallbacks requires it; onTrimMemory carries the signal.")
    override fun onLowMemory() {
        Log.d(TAG, "low memory; flushing")
        sessionDataManager.forceFlush("low_memory")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Rotation is not the end of a session and not a reason to flush.
    }
}
