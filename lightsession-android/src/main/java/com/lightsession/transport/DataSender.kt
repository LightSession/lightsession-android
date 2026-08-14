package com.lightsession.transport
import com.lightsession.LightSessionConfig
import com.lightsession.mapper.ScreenMapperIntegration
import com.lightsession.mapper.SkeletonFrame

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
        /**
         * Pixels per dp on this device.
         *
         * Sent because [width] and [height] cannot answer the question the screen map asks of
         * them. They are resolution; the map draws each screen at a size meant to say how big the
         * device is; density is the only thing that converts one into the other — and low density
         * is exactly what a large screen has.
         *
         * Measured on the two devices this came from: a 1080x2400 phone at 2.625 is 411x914 dp,
         * a 2560x1600 tablet at 2.0 is 1280x800 dp. In pixels the tablet's *height* is the
         * smaller number, so the map drew the physically larger device as the shorter card.
         */
        density: Float,
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
