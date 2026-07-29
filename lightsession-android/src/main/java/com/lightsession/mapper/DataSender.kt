package com.lightsession.mapper

interface DataSender {
    fun setBaseUrl(url: String)
    fun setApiKey(apiKey: String)
    fun getApiKey(): String

    /**
     * Reports a screen.
     *
     * The screen arrives as *either* an encoded bitmap or a [SkeletonFrame], never
     * both — see [com.lightsession.LightSessionConfig.wireframeMode]. Both are
     * nullable so a placeholder can be reported for a screen that had not laid out
     * yet: a navigation still belongs in the graph even when there was nothing to
     * draw.
     */
    suspend fun sendScreenData(
        screenId: String,
        screenName: String,
        screenType: ScreenMapperIntegration.ScreenType,
        bitmapBase64: String?,
        skeleton: SkeletonFrame?,
        width: Int,
        height: Int,
        appVersionCode: Int,
        appVersionName: String,
        theme: String
    ): Result<Unit>

    /**
     * Replaces a screen's image with a real screenshot.
     *
     * [screenName] is the screen's identity and the server requires it. It used to be
     * left out — only the composite [screenId] went on the wire — so every call to this
     * route was rejected with a 422 before it reached a handler. Nothing noticed,
     * because the only caller was dead code.
     */
    suspend fun updateScreenshot(
        screenId: String,
        screenName: String,
        bitmapBase64: String,
        width: Int,
        height: Int,
        appVersionCode: Int,
        appVersionName: String,
        theme: String
    ): Result<Unit>

    suspend fun sendNavigationFlow(
        fromScreen: String,
        toScreen: String,
        transitionType: String,
        timestamp: Long,
        appVersionCode: Int,
        appVersionName: String
    ): Result<Unit>
}
