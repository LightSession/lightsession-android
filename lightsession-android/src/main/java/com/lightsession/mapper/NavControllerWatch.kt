package com.lightsession.mapper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-runs NavController discovery when the composition may have grown one.
 *
 * ## The app this exists for
 *
 * [NavControllerDiscovery] made `withNavigationTracking()` optional, but it ran once, at the end
 * of the grace period — which quietly assumed the `NavHost` exists by then. The first real app
 * integrated broke the assumption in its first file: its `MainActivity` holds the `NavHost` behind
 * a `StateFlow<Destination?>` that starts null and is filled after an auth check, the stock
 * "decide the start destination asynchronously" shape. Resolve in milliseconds — a local token
 * read — and the one-shot scan finds the controller. Resolve slower than the grace period — any
 * network round-trip — and the scan runs against a composition that holds no NavController yet,
 * falls back to the Activity's name, and never looks again. Same wrong map, new cause, and which
 * one a given install gets is decided by its network.
 *
 * ## Why the trigger is a snapshot apply and not a clock
 *
 * Polling would work and would be wrong twice: too slow for the app above and running forever for
 * the app that never navigates. What actually puts a `NavHost` on screen is a state write — the
 * start destination landing *is* `_startDestination.value = ...` — and every state write commits
 * as a snapshot apply. So the moment worth rescanning at announces itself, which is [LateContent]'s
 * argument verbatim; this class is the recurring version of it. While nothing composes, an apply
 * costs one volatile read here, and an app with no state writes at all is an app whose composition
 * cannot have changed — the one case the grace-period scan already covers.
 *
 * Applies say the composition *may* have changed, not that it did, so scans coalesce: one is
 * on the handler at a time, no sooner than [COMPOSITION_SETTLE_MS] after the apply that asked
 * (the write schedules recomposition for a later frame — scanning inside the apply would read the
 * composition from before the write) and no closer than [SCAN_INTERVAL_MS] to the previous scan,
 * so a screen animating at 60 writes a second costs under two scans a second, each a bounded
 * read-only walk.
 *
 * The observer is registered once and kept, gated on [armed] — same reasoning as [LateContent]:
 * [Snapshot.registerApplyObserver] is process-global and disposing a handle from inside its own
 * dispatch is re-entrancy nothing documents. Applies land on whatever thread wrote; the scan
 * always runs on main, where the slot table is written and where the callback registers listeners.
 */
internal class NavControllerWatch(private val onScan: () -> Unit) {

    private val lock = Any()
    private var observer: ObserverHandle? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var armed = false

    /** One scan on the handler at a time; applies that arrive meanwhile fold into it. */
    private val scanPosted = AtomicBoolean(false)

    @Volatile
    private var lastScanUptime = 0L

    private val scan = Runnable {
        scanPosted.set(false)
        if (!armed) return@Runnable
        lastScanUptime = SystemClock.uptimeMillis()
        onScan()
    }

    /** Starts watching. Idempotent; [cancel] is the other half. */
    fun arm() {
        synchronized(lock) {
            ensureObserverLocked()
            armed = true
        }
    }

    /**
     * Stops watching without unregistering. A scan already posted checks [armed] again on the main
     * thread, so cancelling from any thread is enough; the `removeCallbacks` is just tidiness.
     */
    fun cancel() {
        armed = false
        mainHandler.removeCallbacks(scan)
        scanPosted.set(false)
    }

    private fun ensureObserverLocked() {
        if (observer != null) return
        observer = Snapshot.registerApplyObserver { _, _ ->
            if (!armed) return@registerApplyObserver
            if (!scanPosted.compareAndSet(false, true)) return@registerApplyObserver
            val sinceLast = SystemClock.uptimeMillis() - lastScanUptime
            mainHandler.postDelayed(
                scan,
                maxOf(COMPOSITION_SETTLE_MS, SCAN_INTERVAL_MS - sinceLast),
            )
        }
    }

    private companion object {
        /**
         * Long enough for the recomposition the apply scheduled to have committed — a frame is
         * 16 ms, this survives a few dropped ones — and short enough that a controller is
         * registered before a person can navigate anywhere.
         */
        const val COMPOSITION_SETTLE_MS = 150L

        /** Floor between scans, so a write-heavy screen pays for the walk at most ~1.6×/s. */
        const val SCAN_INTERVAL_MS = 600L
    }
}
