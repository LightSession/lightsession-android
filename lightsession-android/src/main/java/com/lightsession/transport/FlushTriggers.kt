package com.lightsession.transport

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lightsession.session.Recording
import com.lightsession.session.SessionDataManager

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
     * True while recording is off *because the app is in the background*, and only then.
     *
     * The flag exists to tell that apart from recording the app itself turned off through
     * `stopRecording`. Foreground must resume the first case and must not touch the second —
     * re-enabling a recorder the app deliberately stopped would record a stretch it asked not to
     * have. So the pause is taken only when recording was on, and the resume only when this pause
     * is the reason it is off.
     */
    private var pausedForBackground = false

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
        // Frames deferred: this is the main thread, and writing a full buffer here is
        // hundreds of file creates while the app is being backgrounded. The breadcrumbs —
        // the part that cannot be reconstructed — are on disk before this returns.
        sessionDataManager.forceFlush("background", deferFrames = true)
        // Stamped *after* the flush, so the timestamp marks when the app actually
        // stopped producing data rather than when the callback happened to run.
        sessionDataManager.markBackgrounded()

        // Recording stops with the app, and this is what makes a backgrounded session actually
        // end. The recorder's idle tick keeps emitting a repeated-frame marker every interval
        // whether the app is in front or not, and each batch it produces resets the server's
        // session key — so a session with the recorder still running never falls idle and is
        // never sealed, however long the app sits in the background. Stopping the tick lets the
        // batches cease, and the server ends the session once its window passes with none
        // arriving. Flushed first, above, so nothing recorded up to this moment is lost.
        //
        // Guarded so this pause is distinguishable from `stopRecording`: only a recorder that was
        // running is paused here, and only such a pause is resumed on return.
        if (Recording.enabled) {
            Recording.enabled = false
            pausedForBackground = true
        }
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
        //
        // Rotation and the server's seal are timed to the same window on purpose: away longer
        // than it and `rotateIfIdle` mints a new id here, which is exactly when the server has
        // also sealed the old one — so the resumed recorder never sends batches under an id the
        // server already closed. Away less than it and neither acts: the same session resumes,
        // its server key never having expired.
        sessionDataManager.rotateIfIdle()
        sessionDataManager.retryPending()

        // Resume only what backgrounding paused. Recording the app itself stopped stays stopped.
        if (pausedForBackground) {
            pausedForBackground = false
            Recording.enabled = true
        }
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
            sessionDataManager.forceFlush("trim_memory", deferFrames = true)
        }
    }

    @Deprecated("Kept because ComponentCallbacks requires it; onTrimMemory carries the signal.")
    override fun onLowMemory() {
        Log.d(TAG, "low memory; flushing")
        sessionDataManager.forceFlush("low_memory", deferFrames = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Rotation is not the end of a session and not a reason to flush.
    }
}
