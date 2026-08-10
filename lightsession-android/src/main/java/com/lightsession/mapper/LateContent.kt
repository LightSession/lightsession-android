package com.lightsession.mapper

import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Fires once when the composition changes after a wireframe was already taken.
 *
 * ## The screen this exists for
 *
 * A screen that loads is two screens: the spinner it shows on arrival and the content it becomes
 * when the data lands. The wireframe was being taken from the first one. Measured on a production
 * `Métricas` screen: the settle detector declared the screen quiet 139 ms after navigation —
 * correctly, because an indeterminate `CircularProgressIndicator` animates without a single
 * snapshot apply or `ViewRootImpl` draw pass, so nothing the detector watches ever moves. The scan
 * then saw the shell and the spinner: 54 rectangles, where the loaded screen measures 98 on the
 * same fixture (`NestedScaffoldNavProbeTest`). No amount of waiting fixes that honestly, and the
 * screenshot path's answer — a flat [ScreenMapperIntegration.SCREENSHOT_SETTLE_MS] delay — is a
 * guess about every app's network from a constant in this one.
 *
 * The data arriving is not silent, though. It lands as a state write — `isLoading = false` through
 * `collectAsStateWithLifecycle` is a `MutableState` write like any other — and every state write
 * is a snapshot apply. So the moment worth recapturing at announces itself, and this class is the
 * announcement: no clock anywhere. What ends the watch is an event too — the arm is one-shot, the
 * mapper re-arms on a budget, and a touch or a navigation cancels it through the same hooks that
 * already cancel a pending capture.
 *
 * ## Why the observer is registered once and kept
 *
 * [Snapshot.registerApplyObserver] is process-global, and disposing a handle from inside its own
 * dispatch is the kind of re-entrancy nothing documents. Registering once and gating on [pending]
 * sidesteps both: disarmed, an apply costs one volatile read against null — the same cost argument
 * [CompositionActivity] makes for its timestamp, and it fires far more often than this does.
 *
 * The callback runs on whatever thread applied the state. Callers hop to main themselves.
 */
internal class LateContent {

    private val lock = Any()
    private var observer: ObserverHandle? = null

    @Volatile
    private var pending: (() -> Unit)? = null

    /**
     * Arms for exactly one apply. Replaces any previous arm — a screen can only be waiting for one
     * arrival at a time, and the newest request is the one that describes the current screen.
     */
    fun arm(onApplied: () -> Unit) {
        synchronized(lock) {
            ensureObserverLocked()
            pending = onApplied
        }
    }

    /** Disarms without firing. Idempotent, called from touch and navigation hooks. */
    fun cancel() {
        pending = null
    }

    private fun ensureObserverLocked() {
        if (observer != null) return
        observer = Snapshot.registerApplyObserver { _, _ ->
            // Claim-then-run, so two applies racing fire the callback once and a cancel that
            // lands between them fires it not at all.
            val claimed = synchronized(lock) {
                pending.also { pending = null }
            }
            claimed?.invoke()
        }
    }
}
