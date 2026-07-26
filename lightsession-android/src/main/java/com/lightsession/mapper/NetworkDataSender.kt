package com.lightsession.mapper

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NetworkDataSender : DataSender {

    // Set by LightSession.init from LightSessionConfig.apiUrl. No default:
    // a hardcoded address is what made the published artifact unusable.
    private var baseUrl: String = ""
    private var apiKey: String = ""

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("X-API-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    companion object {
        private const val TAG = "NetworkDataSender"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    override fun setBaseUrl(url: String) {
        this.baseUrl = url.trimEnd('/')
    }

    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        Log.d(TAG, "API Key configured: ${apiKey.take(8)}...")
    }

    override fun getApiKey(): String {
        return apiKey
    }

    override suspend fun sendScreenData(
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(Exception("API Key not set"))
            }

            val json = JSONObject().apply {
                put("screenId", screenId)
                put("screenName", screenName)
                put("screenType", screenType.name)
                // The key is left out entirely when absent, rather than relying on
                // `put(name, null)` to remove it. Both fields are optional on the
                // server, and "absent" is the state it reads — this way the payload
                // does not depend on a null-handling rule in `JSONObject`.
                bitmapBase64?.let { put("bitmapBase64", it) }
                skeleton?.let { put("skeleton", it.toJson()) }
                put("width", width)
                put("height", height)
                put("appVersionCode", appVersionCode)
                put("appVersionName", appVersionName)
                put("theme", theme)
                put("timestamp", System.currentTimeMillis())
            }

            val request = Request.Builder()
                .url("$baseUrl/screens")
                .post(json.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Screen data sent successfully: $screenId (${width}x${height})")
                Result.success(Unit)
            } else {
                val error = "Failed to send screen data: ${response.code} - ${response.message}"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending screen data", e)
            Result.failure(e)
        }
    }

    override suspend fun updateScreenshot(
        screenId: String,
        screenName: String,
        bitmapBase64: String,
        width: Int,
        height: Int,
        appVersionCode: Int,
        appVersionName: String,
        theme: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(Exception("API Key not set"))
            }

            val json = JSONObject().apply {
                put("screenId", screenId)
                // Required by the server, and its absence here made this route return
                // 422 on every call.
                put("screenName", screenName)
                put("bitmapBase64", bitmapBase64)
                put("width", width)
                put("height", height)
                put("appVersionCode", appVersionCode)
                put("appVersionName", appVersionName)
                put("theme", theme)
                put("timestamp", System.currentTimeMillis())
            }

            val request = Request.Builder()
                .url("$baseUrl/screens/screenshot")
                .put(json.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Screenshot updated successfully: $screenId (${width}x${height})")
                Result.success(Unit)
            } else {
                val error = "Failed to update screenshot: ${response.code} - ${response.message}"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating screenshot", e)
            Result.failure(e)
        }
    }

    override suspend fun sendNavigationFlow(
        fromScreen: String,
        toScreen: String,
        transitionType: String,
        timestamp: Long,
        appVersionCode: Int,
        appVersionName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(Exception("API Key not set"))
            }

            val json = JSONObject().apply {
                put("from", fromScreen)
                put("to", toScreen)
                put("type", transitionType)
                put("timestamp", timestamp)
                put("appVersionCode", appVersionCode)
                put("appVersionName", appVersionName)
            }

            val request = Request.Builder()
                .url("$baseUrl/flows")
                .post(json.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Navigation flow sent successfully: $fromScreen -> $toScreen")
                Result.success(Unit)
            } else {
                val error = "Failed to send navigation flow: ${response.code} - ${response.message}"
                Log.e(TAG, error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending navigation flow", e)
            Result.failure(e)
        }
    }
}