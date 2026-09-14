package com.lightsession.session
import com.lightsession.LightSession
import com.lightsession.LightSessionConfig
import com.lightsession.masking.Masking

/**
 * Whether the SDK is recording right now.
 *
 * ## Why a global, consulted at the point of production
 *
 * The things that produce recorded data are scattered by nature: a `Window.Callback` wrapping
 * every Activity, a tick on a scheduled executor, an Activity lifecycle callback, a navigation
 * listener. Threading a flag through all of them means the next producer someone adds can forget
 * to check it, and forgetting produces data — the wrong direction to fail in. A flag read where
 * the work happens makes forgetting the exception rather than the default.
 *
 * Same shape as [Masking] for the same reason, and read the same way: `@Volatile` with an
 * internal setter, so the SDK writes it and the host app can only ask.
 *
 * ## What "off" covers
 *
 * Everything about what a person did, and every picture of what they saw:
 *
 *  * frames are not captured — which also skips the mask traversal, the expensive part;
 *  * taps and swipes are not turned into events;
 *  * navigations are not recorded;
 *  * screens are not captured for the screen map.
 *
 * That last one is a judgement call worth stating. The screen map describes the *app*, not the
 * person, so an argument exists for leaving it on. It loses: a client who says "do not record
 * the splash" is asking for no picture of the splash, and a wireframe of it is a picture. When
 * reading intent for a switch whose whole purpose is to collect less, the cautious reading wins.
 *
 * What is *not* affected: [LightSession.identify] and [LightSession.reset]. Knowing who someone
 * is belongs to the account, not to a recording, and an app that identifies while recording is
 * off should not have to re-identify when it turns on.
 *
 * ## Where the gate is checked twice
 *
 * At each producer, to avoid the work, and again at [SessionDataManager]'s three intake methods,
 * which is the only place all recorded data converges. The second check earns its keep: it is
 * what makes a producer that forgets cost a wasted capture rather than a leak.
 */
internal object Recording {

    /**
     * On by default, matching every version before this switch existed.
     *
     * An SDK that stopped recording on upgrade because a new flag defaulted off would be a silent
     * outage in every app that took the update. See
     * [LightSessionConfig.startRecordingOnInit] for the knob that turns it off deliberately.
     */
    @Volatile
    var enabled: Boolean = true
        internal set

    /**
     * True while recording is off *because the app went to background*, and only then.
     *
     * It lives here, beside the gate it modifies, because two callers coordinate through it and
     * neither can see the other: `FlushTriggers` pauses on background and resumes on return, and
     * `LightSession.stopRecording` must be able to cancel a pending resume. The first version kept
     * this flag private to `FlushTriggers`, and that produced a shipped bug: an explicit
     * `stopRecording()` while background-paused hit `if (!enabled) return` and left the flag
     * standing, so the next foreground silently resumed recording the app had just asked to stop.
     */
    @Volatile
    private var pausedForBackground: Boolean = false

    /**
     * Backgrounding stops a running recorder, and remembers that *it* was the one to do so.
     *
     * A recorder that was already off is left alone — the app stopped it, and the app's decision
     * outranks the lifecycle's.
     */
    internal fun pauseForBackground() {
        if (enabled) {
            enabled = false
            pausedForBackground = true
        }
    }

    /**
     * Foreground resumes only what backgrounding paused. Returns whether it did, for the caller's
     * log line.
     */
    internal fun resumeFromBackground(): Boolean {
        if (!pausedForBackground) return false
        pausedForBackground = false
        enabled = true
        return true
    }

    /**
     * The app spoke: whatever it says next is the whole truth, so a pending background resume is
     * cancelled. Called by both `startRecording` and `stopRecording` before they consult [enabled]
     * — an explicit stop while paused must stick across the next foreground, and an explicit start
     * makes the pause moot.
     */
    internal fun appOverrides() {
        pausedForBackground = false
    }
}
