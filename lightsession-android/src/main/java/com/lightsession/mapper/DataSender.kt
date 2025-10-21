package com.lightsession.mapper

interface DataSender {
    fun setBaseUrl(url: String)
    fun setApiKey(apiKey: String)

    suspend fun sendScreenData(
        screenId: String,
        screenName: String,
        screenType: ScreenMapperIntegration.ScreenType,
        bitmapBase64: String,
        width: Int,
        height: Int,
        appVersionCode: Int,
        appVersionName: String,
        theme: String // Novo parâmetro: theme
    ): Result<Unit>

    suspend fun updateScreenshot(
        screenId: String,
        bitmapBase64: String,
        width: Int,
        height: Int,
        appVersionCode: Int,
        appVersionName: String,
        theme: String // Novo parâmetro: theme
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