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

    fun init(application: Application, config: LightSessionConfig) {
        if (isInitialized) {
            return
        }
        this.accessKey = config.apiKey
        this.config = config
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