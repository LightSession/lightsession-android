package com.lightsession.mapper

import java.util.concurrent.atomic.AtomicLong

/**
 * Whether one screen is currently replacing another.
 *
 * ## What this replaces, and why it had to
 *
 * The recorder needs to know not to capture during a screen change: a frame taken mid-crossfade
 * shows both screens, and [CompositionActivity] explains why the mask cannot be made right for such
 * a frame. That much is unchanged.
 *
 * What changed is the signal. The recorder used to ask [CompositionActivity] directly — "did the
 * composition change in the last 120 ms" — and treat any yes as a transition. But that observer
 * fires on *every* Compose state application, and a scrolling `LazyColumn` applies state on every
 * frame. So an ordinary scroll looked exactly like a crossfade, and the recorder suppressed all of
 * it.
 *
 * Measured on a Galaxy Tab A7, twenty-second stretches of recording a scrolled list:
 *
 * ```
 * pause between gestures   real frames   repeat markers
 *              0 ms             1             196
 *            300 ms             2             174
 *            800 ms            18             148
 * ```
 *
 * The 18 at 800 ms are the quiet seconds between drags, captured at the idle interval. While the
 * finger was down, essentially nothing was captured — and that is precisely when
 * `interactionCaptureIntervalMs` had accelerated the loop tenfold to capture *more*. The burst was
 * running at ten times the rate and producing nothing but repeat markers.
 *
 * ## The signal it uses instead
 *
 * A transition is something the screen mapper already knows about: it is told when a destination
 * changes, by an Activity resuming, a `NavController` reporting, or the host calling `setScreen`.
 * [begin] is called from there. Scrolling never calls it, so scrolling is never mistaken for a
 * transition.
 *
 * [CompositionActivity] is still what closes the window, and it is good at that: a crossfade keeps
 * the composition busy for exactly as long as it runs, so "quiet for 120 ms" is a precise end. The
 * two signals do different jobs — one says a transition started, the other says it finished.
 *
 * ## Why there is a cap
 *
 * Because "the composition went quiet" is not guaranteed to arrive. Navigate to a screen with a
 * loading spinner and the composition never goes quiet at all; under the old logic that screen was
 * suppressed for as long as it kept spinning, which could be the whole visit. [MAX_MS] bounds it.
 * Past the cap the frame ships even if something is still animating — a spinner is not two screens,
 * and a capture with one honest mask beats no capture at all.
 */
internal object ScreenTransition {

    /** When the current transition was announced, or 0 when none is in flight. */
    private val startedAt = AtomicLong(0)

    /**
     * How long the composition must be quiet for the transition to count as finished.
     *
     * A little above one frame at 60 Hz, which is the value the recorder used for the same purpose
     * before this class existed.
     */
    private const val QUIET_MS = 120L

    /**
     * How long after [begin] a transition is assumed to be running regardless.
     *
     * The arriving screen has not composed yet at the instant the destination is reported — the
     * `NavController` announces the change before its content exists — so asking
     * [CompositionActivity] immediately would find nothing moving and declare the transition over
     * before it began.
     */
    private const val GRACE_MS = 120L

    /**
     * The longest a transition can suppress capture, however busy the composition stays.
     *
     * Generous enough for the 1500 ms fade measured in [CompositionActivity], short enough that a
     * screen which simply never settles costs two seconds of recording rather than all of it.
     */
    private const val MAX_MS = 2_000L

    /** Announces that a destination changed. Called by the screen mapper, never by the recorder. */
    fun begin(now: Long = System.currentTimeMillis()) {
        startedAt.set(now)
    }

    /**
     * Whether a screen change is still in flight.
     *
     * Clears the window as a side effect once it ends, so the answer costs one atomic read on the
     * overwhelmingly common path where nothing is transitioning.
     */
    fun inProgress(now: Long = System.currentTimeMillis()): Boolean {
        val started = startedAt.get()
        if (started == 0L) return false

        val age = now - started
        if (age !in 0L..<MAX_MS) {
            startedAt.compareAndSet(started, 0L)
            return false
        }
        if (age < GRACE_MS) return true

        if (!CompositionActivity.movingWithin(QUIET_MS, now)) {
            startedAt.compareAndSet(started, 0L)
            return false
        }
        return true
    }

    /** Forgets any transition in flight. For recorder shutdown, and for tests. */
    fun reset() {
        startedAt.set(0L)
    }
}
