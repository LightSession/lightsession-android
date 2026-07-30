package com.lightsession

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lightsession.mapper.NetworkDataSender
import com.lightsession.mapper.ScreenMapperIntegration
import com.lightsession.replay.ReplayIntegration
import java.util.*

class LightSession private constructor() {
    companion object {
        @Volatile
        private var instance: LightSession? = null

        @JvmStatic
        fun getInstance(): LightSession {
            return instance ?: synchronized(this) {
                instance ?: LightSession().also { instance = it }
            }
        }

        private const val PREFS_NAME = "LightSessionPrefs"
        private const val ANONYMOUS_ID_KEY = "anonymous_user_id"
    }

    private var accessKey: String? = null
    private var isInitialized = false
    private lateinit var config: LightSessionConfig
    private lateinit var sessionDataManager: SessionDataManager

    /** Who the session belongs to. See [Identity]. */
    private lateinit var identity: Identity

    private var replayIntegration: ReplayIntegration? = null
    private var flushTriggers: FlushTriggers? = null

    /**
     * Names a part of the current screen that the SDK cannot recognise on its own.
     *
     * Dialogs and modal bottom sheets are windows, so they are detected without help. What
     * is not detectable is anything drawn inside the composition — a `BottomSheetScaffold`
     * sheet, a panel behind `AnimatedVisibility`, a full-screen step in a wizard. The
     * measurement behind that claim is in `ComposeOverlayProbeTest`: an expanded sheet's
     * only marker sits on its 127px drag handle, which is indistinguishable from an
     * expanded row in a list, and guessing would turn every such row into a screen.
     *
     * The name becomes a suffix on the current screen — `doctors › filter-sheet` — with its
     * own capture, heatmap and edges in the flow map. Use a fixed string, not something
     * built from the data on display: "filter-sheet" is a screen, `"Dr. \${doctor.name}"` is
     * one screen per doctor.
     *
     * Pass null when it closes. Prefer [com.lightsession.mapper.LightSessionSubScreen] in
     * Compose, which does both ends for you.
     */
    fun setSubScreen(name: String?) {
        if (!isInitialized) return
        ScreenMapperIntegration.getInstance().setDeclaredSubScreen(name)
    }

    /**
     * Undoes [setSubScreen], but only if [name] is still the one showing.
     *
     * The guard is what makes overlapping panels safe. When one sheet replaces another the
     * arriving one declares itself before the leaving one is disposed, so an unconditional
     * clear would erase a claim that had already moved on.
     */
    fun clearSubScreen(name: String) {
        if (!isInitialized) return
        ScreenMapperIntegration.getInstance().clearDeclaredSubScreen(name)
    }

    /**
     * Says who is using the app.
     *
     * `userId` is the app's own identifier for the person — whatever its database calls it.
     * From here on their sessions are recorded under it, and the server is told that this
     * install belonged to them, so everything this device did *before* this call becomes
     * theirs too. That is the part worth having: the sign-up screen somebody abandoned is
     * recorded before there is anyone to attribute it to.
     *
     * `traits` is optional and nothing is collected automatically. Send an id alone and no
     * personal data leaves the device, which is the setting that needs no justification.
     * Whatever is sent is stored against the person and merged with what was sent before, so
     * `identify(id, mapOf("plan" to "pro"))` after `identify(id, mapOf("email" to ...))` keeps
     * both. Strings, numbers and booleans only — anything else is dropped with a warning
     * rather than turned into `com.acme.User@3f2a1b`.
     *
     * Cheap to call repeatedly: an identify is only sent when the id actually changes, so
     * calling this on every screen costs nothing. Call [reset] on sign-out.
     */
    fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) {
        if (!isInitialized) {
            Log.w("LightSession", "identify before init; ignored")
            return
        }
        val changed = identity.identify(userId)
        // Sent when the traits alone changed as well, since the caller went to the trouble of
        // passing them and the server merges rather than replaces.
        if (changed || traits.isNotEmpty()) {
            sessionDataManager.addIdentify(identity.effectiveId, identity.anonymousId, traits)
        }
    }

    /**
     * Forgets who was using the app. Call this on sign-out.
     *
     * The device gets a fresh anonymous id, which is the part that matters: keeping the old
     * one would tie it to the person who just left, so whoever signs in next inherits their
     * history and two people become one. A new session is started for the same reason — one
     * session holding two people is a replay of nobody.
     */
    fun reset() {
        if (!isInitialized) return
        identity.reset()
        sessionDataManager.startNewSession("identity_reset")
        Log.d("LightSession", "reset")
    }

    /** Whether anything is being recorded right now. */
    val isRecording: Boolean
        get() = Recording.enabled

    /**
     * Starts recording, as a new session.
     *
     * For an app that wants one flow captured rather than everything from the splash onward:
     * configure [LightSessionConfig.startRecordingOnInit] off, then call this when the flow
     * begins and [stopRecording] when it ends.
     *
     * ## Why this rolls the session instead of resuming one
     *
     * A session's replay is rendered as one video, and the renderer holds the last frame it has
     * across any gap in the timeline. Resuming inside one session would therefore produce a video
     * in which the app appears frozen for however long recording was off, beside an event list
     * with an unexplained hole in it — a replay that describes something that did not happen.
     *
     * Rolled, each recorded stretch is a complete and honest replay of itself. The two stretches
     * are still one person's: [identify] and the anonymous-id alias are what tie them together,
     * and they are unaffected by this.
     *
     * Calling this while already recording does nothing — deliberately, and not just for safety.
     * A screen that starts recording in `onResume` would otherwise split a session every time it
     * came back to the foreground.
     */
    fun startRecording() {
        if (!isInitialized) {
            Log.w("LightSession", "startRecording before init; ignored")
            return
        }
        if (Recording.enabled) return

        // Rolled before the flag flips, so nothing from this moment lands in the session that
        // was open while recording was off.
        sessionDataManager.startNewSession("recording_started")
        Recording.enabled = true
        Log.i("LightSession", "recording started")
    }

    /**
     * Stops recording, and sends what has been recorded so far.
     *
     * Flushed rather than discarded: everything up to this call was collected while recording was
     * on, which is what the app asked for. It also means the session closes on the server without
     * waiting out the idle timeout, so the replay is available sooner.
     *
     * After this, no frame is captured, no tap or navigation becomes an event, and no screen is
     * captured for the screen map. See [Recording] for why the screen map is included.
     */
    fun stopRecording() {
        if (!isInitialized) {
            Log.w("LightSession", "stopRecording before init; ignored")
            return
        }
        if (!Recording.enabled) return

        // Flag first, so nothing new arrives while the flush is in flight.
        Recording.enabled = false
        sessionDataManager.forceFlush("recording_stopped")
        Log.i("LightSession", "recording stopped")
    }

    fun init(application: Application, config: LightSessionConfig) {
        if (isInitialized) {
            return
        }
        this.accessKey = config.apiKey
        this.config = config
        // First, because it decides what "the screen" means for everything below: the bitmap a
        // capture allocates, the size recorded against it, and the divisor every touch coordinate
        // is normalised by. Un-attached it falls back to a metrics object shared with the whole
        // process, which is exactly what [ScreenGeometry] exists to stop depending on.
        ScreenGeometry.attach(application)
        // Set before anything can capture. `ScreenDrawing` consults this at capture
        // time, so a recorder started later still masks.
        Masking.configure(config)
        // Same ordering, and for a stronger reason: every producer reads this, and one that
        // starts before it is set would record a stretch the app asked not to have.
        Recording.enabled = config.startRecordingOnInit
        this.isInitialized = true

        identity = Identity.from(application.applicationContext)
        sessionDataManager = SessionDataManager(application.applicationContext, config)
        sessionDataManager.init(identity)

        // Without this the only flush that ever runs is the five-second ticker:
        // `onTerminate`, `onLowMemory` and `onDestroy` were all written and none of
        // them had a caller, so closing the app dropped whatever was buffered.
        flushTriggers = FlushTriggers(sessionDataManager).also { it.register(application) }

        if (this.config.enableReplay) {
            replayIntegration = ReplayIntegration(application.applicationContext, config)
            replayIntegration?.init(sessionDataManager)
        }

        val networkDataSender = NetworkDataSender()
        networkDataSender.setApiKey(config.apiKey)
        networkDataSender.setBaseUrl("${config.normalizedApiUrl}/api/v1/screenmap")

        ScreenMapperIntegration.getInstance().init(
            application = application,
            dataSender = networkDataSender,
            sessionDataManager = sessionDataManager,
            wireframeMode = config.wireframeMode,
            captureRealScreens = config.captureRealScreens,
            trackTabs = config.trackTabs,
            trackModals = config.trackModals,
            trueColourWireframes = config.trueColourWireframes,
        )
    }

}
