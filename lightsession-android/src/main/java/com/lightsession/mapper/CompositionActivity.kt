package com.lightsession.mapper

import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * When the composition last changed, for anything that needs to know the screen is still moving.
 *
 * ## Why this exists apart from [ComposeSettleDetector]
 *
 * That class answers "tell me when this screen has settled", once, for one navigation. This
 * answers "is anything moving right now", continuously, for a recorder that has to decide
 * several times a second whether the frame in front of it is worth keeping.
 *
 * ## What it is for
 *
 * A frame captured while one screen is replacing another shows both of them, because that is
 * what a crossfade *is* — and the masker covers both, because at half opacity both are genuinely
 * readable. Measured on a 1500 ms fade, halfway through: two rectangles, one over each screen's
 * text. Nothing in Compose's public API separates the outgoing screen from the incoming one —
 * `isPlaced`, `isAttached` and the node bounds are all valid for both, and `isTransparent` never
 * reports true because alpha reaches zero only at the instant the node leaves the tree.
 *
 * So the mask cannot be made right for such a frame. Masking only the incoming screen would
 * leave the outgoing one's text legible under nothing, which is a leak rather than an eyesore.
 * The frame itself is the problem, and the fix is to not send it: while this reports movement,
 * the recorder repeats the previous frame instead of capturing a new one.
 *
 * Repeating rather than dropping is the whole difference from an earlier attempt at this. A
 * dropped frame leaves a gap, and the renderer holds whatever came before it across the gap —
 * which made the artefact last *longer*. A repeat is the signal the renderer already understands.
 *
 * ## Cost
 *
 * One timestamp write per state application, on whichever thread applied it. The observer is
 * registered once for the process and never scans anything; [movingWithin] is a subtraction.
 */
internal object CompositionActivity {

    /** Registered once. A second registration would double every write for no gain. */
    private var observer: androidx.compose.runtime.snapshots.ObserverHandle? = null

    private val lastChangeMs = AtomicLong(0)

    /** True once [start] has run, so callers can tell "quiet" from "not watching". */
    @Volatile
    var watching: Boolean = false
        private set

    /**
     * Begins watching. Idempotent.
     *
     * Safe to call from any thread and cheap to call again: a composition-heavy app starts this
     * once and the recorder does not have to know whether the mapper got there first.
     */
    @Synchronized
    fun start() {
        if (observer != null) return
        observer = Snapshot.registerApplyObserver { _, _ ->
            lastChangeMs.set(System.currentTimeMillis())
        }
        watching = true
    }

    @Synchronized
    fun stop() {
        observer?.dispose()
        observer = null
        watching = false
        lastChangeMs.set(0)
    }

    /**
     * Whether the composition changed within the last [windowMs].
     *
     * False when nothing is being watched, so a host without Compose — or one where the
     * observer could not be registered — records exactly as it did before rather than silently
     * recording nothing.
     */
    fun movingWithin(windowMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (!watching) return false
        val last = lastChangeMs.get()
        if (last == 0L) return false
        return now - last < windowMs
    }

    /** For tests: pretend the composition changed at `atMs`. */
    internal fun noteChangeForTest(atMs: Long) {
        watching = true
        lastChangeMs.set(atMs)
    }

    /** For tests: forget everything, without touching the real observer. */
    internal fun resetForTest() {
        watching = false
        lastChangeMs.set(0)
    }
}
