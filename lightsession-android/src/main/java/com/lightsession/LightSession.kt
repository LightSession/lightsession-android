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

    private var distinctId: String? = null
    private var userIdentifiedExplicitly = false

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

    fun init(application: Application, config: LightSessionConfig) {
        if (isInitialized) {
            return
        }
        this.accessKey = config.apiKey
        this.config = config
        // Set before anything can capture. `ScreenDrawing` consults this at capture
        // time, so a recorder started later still masks.
        Masking.configure(config)
        this.isInitialized = true

        initializeUserId(application.applicationContext)
        sessionDataManager = SessionDataManager(application.applicationContext, config)
        sessionDataManager.init()

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

        // Passar o networkDataSender configurado para o ScreenMapperIntegration
        ScreenMapperIntegration.getInstance().init(
            application = application,
            dataSender = networkDataSender,
            sessionDataManager = sessionDataManager,
            wireframeMode = config.wireframeMode,
            captureRealScreens = config.captureRealScreens,
            trackTabs = config.trackTabs,
            trackModals = config.trackModals,
        )
    }

    private fun initializeUserId(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAnonymousId = prefs.getString(ANONYMOUS_ID_KEY, null)

        if (!savedAnonymousId.isNullOrBlank()) {
            this.distinctId = savedAnonymousId
            this.userIdentifiedExplicitly = false
            Log.d("LightSession", "Loaded existing anonymous user ID: ${this.distinctId}")
        } else {
            this.distinctId = UUID.randomUUID().toString()
            prefs.edit().putString(ANONYMOUS_ID_KEY, this.distinctId).apply()
            this.userIdentifiedExplicitly = false
            Log.d("LightSession", "Generated and saved new anonymous user ID: ${this.distinctId}")
        }
    }
}