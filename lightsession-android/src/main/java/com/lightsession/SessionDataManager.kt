package com.lightsession

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages all session data including video frames, navigation events, and user interactions.
 * Batches all data together for synchronized sending to the server.
 */
class SessionDataManager(
    private val context: Context,
    private val config: LightSessionConfig
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Session identification
    private val sessionId = System.currentTimeMillis().toString()
    private var sessionStartTime = System.currentTimeMillis()

    // Batch configuration
    private val BATCH_INTERVAL_MS = 5000L // 5 s    econds
    private val MAX_BATCH_SIZE_MB = 50

    // HTTP client
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // Data structures for different types of events
    private val frameBuffer = ConcurrentLinkedQueue<FrameData>()
    private val navigationBuffer = ConcurrentLinkedQueue<NavigationEvent>()
    private val interactionBuffer = ConcurrentLinkedQueue<InteractionEvent>()

    // Sequence tracking
    private val globalSequenceCounter = AtomicInteger(0)
    private val frameSequenceCounter = AtomicInteger(0)
    private var baseUrl: String = "http://192.168.0.114:5000" // Default para emulador


    // Statistics
    private var totalBatchesSent = 0
    private var totalFramesSent = 0
    private var totalNavigationsSent = 0
    private var totalInteractionsSent = 0

    /**
     * Base class for all session events with timestamp and sequence
     */
    @Serializable
    sealed class SessionEvent {
        abstract val timestamp: Long
        abstract val sequenceNumber: Int
        abstract val eventType: String
    }

    /**
     * Frame data from video capture
     */
    @Serializable
    data class FrameData(
        override val timestamp: Long,
        override val sequenceNumber: Int,
        override val eventType: String = "frame",
        val frameSequenceNumber: Int,
        val imageData: ByteArray,
        val isRepeatedFrame: Boolean = false,
        val currentScreen: String? = null
    ) : SessionEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FrameData
            return timestamp == other.timestamp && sequenceNumber == other.sequenceNumber
        }

        override fun hashCode(): Int {
            return timestamp.hashCode() * 31 + sequenceNumber
        }
    }

    /**
     * Navigation between screens
     */
    @Serializable
    data class NavigationEvent(
        override val timestamp: Long,
        override val sequenceNumber: Int,
        override val eventType: String = "navigation",
        val fromScreen: String,
        val toScreen: String,
        val screenType: String,
        val transitionType: String = "navigation"
    ) : SessionEvent()

    /**
     * User interaction (tap, swipe, click)
     */
    @Serializable
    data class InteractionEvent(
        override val timestamp: Long,
        override val sequenceNumber: Int,
        override val eventType: String = "interaction",
        val interactionType: String, // TAP, SWIPE, CLICK
        val targetElement: String? = null,
        val targetElementType: String? = null,
        val resourceId: String? = null,
        val screen: String,
        val coordinates: Coordinates,
        val duration: Long? = null,
        val path: List<Coordinates>? = null, // For swipe gestures
        val rawInteractionData: String? = null // Store the original JSON for breadcrumb
    ) : SessionEvent()

    @Serializable
    data class Coordinates(val x: Float, val y: Float)

    /**
     * Complete batch containing all types of events
     */
    @Serializable
    data class SessionBatch(
        val batchId: String,
        val sessionId: String,
        val batchNumber: Int,
        val timestamp: Long,
        val events: List<SessionEvent>,
        val metadata: BatchMetadata
    )

    /**
     * User information for session tracking
     */
    @Serializable
    data class UserInfo(
        val userId: String,
        val userType: String, // "anonymous" or "identified"
        val sessionId: String,
        val sessionStartTime: Long
    )

    @Serializable
    data class BatchMetadata(
        val frameCount: Int,
        val navigationCount: Int,
        val interactionCount: Int,
        val batchSizeBytes: Long,
        val timeSinceSessionStart: Long,
        val deviceInfo: DeviceInfo,
        val userInfo: UserInfo,
        val appInfo: AppInfo
    )

    @Serializable
    data class DeviceInfo(
        val screenWidth: Int,
        val screenHeight: Int,
        val density: Float,
        val androidVersion: String,
        val deviceModel: String,
        val manufacturer: String,
        val locationInfo: LocationInfo? = null
    )

    /**
     * IP-based location information
     */
    @Serializable
    data class LocationInfo(
        val ip: String,
        val city: String? = null,
        val region: String? = null,
        val country: String? = null,
        val loc: String? = null,
        val org: String? = null,
        val postal: String? = null,
        val timezone: String? = null
    )

    /**
     * App information for session tracking
     */
    @Serializable
    data class AppInfo(
        val version: String,
        val versionCode: Int,
        val packageName: String,
        val buildType: String,
        val compileSdkVersion: Int
    )

    private var userId: String? = null // Store user ID
    private var userType: String = "anonymous" // "anonymous" or "identified"
    private var appVersion: String = getAppVersion(context)

    // Add cache for location info to avoid repeated API calls
    private var cachedLocationInfo: LocationInfo? = null
    private var locationInfoTimestamp: Long = 0
    private val LOCATION_CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Generate anonymous user ID
     */
    private fun generateAnonymousId(): String {
        val prefs = context.getSharedPreferences("LightSessionPrefs", Context.MODE_PRIVATE)
        val existingId = prefs.getString("anonymous_user_id", null)

        return if (!existingId.isNullOrBlank()) {
            existingId
        } else {
            val newId = "anon_${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString("anonymous_user_id", newId).apply()
            newId
        }
    }


    /**
     * Initialize the session manager with user context
     */
    fun init(userId: String? = null, userType: String = "anonymous") {
        this.userId = userId ?: generateAnonymousId()
        this.userType = userType
        this.appVersion = getAppVersion(context)

        sessionStartTime = System.currentTimeMillis()

        // Fetch location info on initialization
        coroutineScope.launch {
            fetchLocationInfo()
        }

        startBatchProcessor()

        Log.d("SessionDataManager",
            "Initialized session: $sessionId for user: ${this.userId} (type: ${this.userType})")
    }

    /**
     * Add a frame from video capture
     */
    fun addFrame(
        imageData: ByteArray,
        isRepeatedFrame: Boolean,
        currentScreen: String? = null
    ) {
        val frame = FrameData(
            timestamp = System.currentTimeMillis(),
            sequenceNumber = globalSequenceCounter.incrementAndGet(),
            frameSequenceNumber = frameSequenceCounter.incrementAndGet(),
            imageData = imageData,
            isRepeatedFrame = isRepeatedFrame,
            currentScreen = currentScreen
        )

        frameBuffer.offer(frame)

        Log.d("SessionDataManager",
            "Frame added: seq=${frame.sequenceNumber}, " +
                    "frameSeq=${frame.frameSequenceNumber}, " +
                    "repeated=$isRepeatedFrame, screen=$currentScreen")
    }

    /**
     * Add a navigation event
     */
    fun addNavigation(
        fromScreen: String,
        toScreen: String,
        screenType: String,
        transitionType: String = "navigation"
    ) {
        val navigation = NavigationEvent(
            timestamp = System.currentTimeMillis(),
            sequenceNumber = globalSequenceCounter.incrementAndGet(),
            fromScreen = fromScreen,
            toScreen = toScreen,
            screenType = screenType,
            transitionType = transitionType
        )

        navigationBuffer.offer(navigation)

        Log.d("SessionDataManager",
            "Navigation added: seq=${navigation.sequenceNumber}, " +
                    "$fromScreen -> $toScreen")
    }

    /**
     * Add a user interaction event from InteractionAwareCallback
     * This receives the raw JSON data from the interaction
     */
    fun addInteractionFromJson(interactionData: String) {
        try {
            val jsonObject = JSONObject(interactionData)
            val type = jsonObject.getString("type")
            val startTime = jsonObject.getLong("start_time")
            val endTime = jsonObject.getLong("end_time")
            val pointsArray = jsonObject.getJSONArray("points")

            // Extract screen and coordinates from the first point
            var screen = ""
            val coordinates = mutableListOf<Coordinates>()

            if (pointsArray.length() > 0) {
                val firstPoint = pointsArray.getJSONObject(0)
                screen = firstPoint.optString("screen", "unknown")

                // Extract all coordinates for the path
                for (i in 0 until pointsArray.length()) {
                    val point = pointsArray.getJSONObject(i)
                    coordinates.add(
                        Coordinates(
                        point.getDouble("x").toFloat(),
                        point.getDouble("y").toFloat()
                    )
                    )
                }
            }

            val interaction = InteractionEvent(
                timestamp = System.currentTimeMillis(),
                sequenceNumber = globalSequenceCounter.incrementAndGet(),
                interactionType = type,
                targetElement = null, // Not available in the current interactionData
                targetElementType = null,
                resourceId = null,
                screen = screen,
                coordinates = coordinates.firstOrNull() ?: Coordinates(0f, 0f),
                duration = endTime - startTime,
                path = if (type == "SWIPE" && coordinates.size > 1) coordinates else null,
                rawInteractionData = interactionData // Store raw data for breadcrumb
            )

            interactionBuffer.offer(interaction)

            Log.d("SessionDataManager",
                "Interaction added from JSON: seq=${interaction.sequenceNumber}, " +
                        "type=$type, screen=$screen")
        } catch (e: Exception) {
            Log.e("SessionDataManager", "Error parsing interaction JSON", e)
        }
    }

    /**
     * Start the automatic batch processor
     */
    private fun startBatchProcessor() {
        coroutineScope.launch {
            while (isActive) {
                delay(BATCH_INTERVAL_MS)
                processBatch("scheduled")
            }
        }
    }

    /**
     * Process and send a batch of all accumulated events
     */
    private fun processBatch(reason: String) {
        // Collect all events from buffers
        val allEvents = mutableListOf<SessionEvent>()

        // Drain frame buffer
        val frames = mutableListOf<FrameData>()
        while (true) {
            val frame = frameBuffer.poll() ?: break
            frames.add(frame)
            allEvents.add(frame)
        }

        // Drain navigation buffer
        val navigations = mutableListOf<NavigationEvent>()
        while (true) {
            val nav = navigationBuffer.poll() ?: break
            navigations.add(nav)
            allEvents.add(nav)
        }

        // Drain interaction buffer
        val interactions = mutableListOf<InteractionEvent>()
        while (true) {
            val interaction = interactionBuffer.poll() ?: break
            interactions.add(interaction)
            allEvents.add(interaction)
        }

        if (allEvents.isEmpty()) {
            Log.d("SessionDataManager", "No events to batch")
            return
        }

        // Sort all events by sequence number to maintain order
        allEvents.sortBy { it.sequenceNumber }

        // Calculate batch size
        val batchSizeBytes = frames.sumOf { it.imageData.size } +
                (navigations.size * 200) + // Estimate for navigation events
                (interactions.size * 300)   // Estimate for interaction events

        val batch = SessionBatch(
            batchId = "${sessionId}_${System.currentTimeMillis()}",
            sessionId = sessionId,
            batchNumber = ++totalBatchesSent,
            timestamp = System.currentTimeMillis(),
            events = allEvents,
            metadata = BatchMetadata(
                frameCount = frames.size,
                navigationCount = navigations.size,
                interactionCount = interactions.size,
                batchSizeBytes = batchSizeBytes.toLong(),
                timeSinceSessionStart = System.currentTimeMillis() - sessionStartTime,
                deviceInfo = getDeviceInfo(),
                userInfo = UserInfo(userId ?: "unknown", userType, sessionId, sessionStartTime),
                appInfo = getAppInfo() // <-- ADD THIS
            )
        )

        Log.d("SessionDataManager",
            "Processing batch #${batch.batchNumber}: " +
                    "${frames.size} frames, ${navigations.size} navigations, " +
                    "${interactions.size} interactions (reason: $reason)")

        coroutineScope.launch {
            sendBatch(batch, frames)
        }
    }

    /**
     * Send batch to server - keeping the video batch format intact
     * and adding breadcrumb data separately
     */
    private fun sendBatch(batch: SessionBatch, frames: List<FrameData>) {
        try {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)

            val frameEvents = batch.events.filterIsInstance<FrameData>()
            val navigationEvents = batch.events.filterIsInstance<NavigationEvent>()
            val interactionEvents = batch.events.filterIsInstance<InteractionEvent>()

            if (frames.isNotEmpty()) {
                multipartBuilder.addFormDataPart("type", "frame_batch")

                val batchMetadata = mapOf(
                    "batch_id" to batch.batchId,
                    "session_id" to batch.sessionId,
                    "user_id" to (userId ?: "unknown"),
                    "user_type" to userType,
                    "app_version" to appVersion,
                    "total_frame_count" to frames.size.toString(),
                    "real_frame_count" to frames.count { !it.isRepeatedFrame }.toString(),
                    "repeated_signal_count" to frames.count { it.isRepeatedFrame }.toString(),
                    "flush_reason" to "scheduled",
                    "total_batches_sent" to batch.batchNumber.toString(),
                    "first_frame_timestamp" to frames.first().timestamp.toString(),
                    "last_frame_timestamp" to frames.last().timestamp.toString(),
                    "sequence_range" to "${frames.first().frameSequenceNumber}-${frames.last().frameSequenceNumber}"
                )

                multipartBuilder.addFormDataPart("metadata", Json.encodeToString(batchMetadata))

                // Add frames (keeping original format)
                frames.forEachIndexed { index, frame ->
                    val frameMetadata = mapOf(
                        "timestamp" to frame.timestamp.toString(),
                        "sequence_number" to frame.frameSequenceNumber.toString(),
                        "frame_index" to index.toString(),
                        "is_repeated_frame" to frame.isRepeatedFrame.toString(),
                        "frame_type" to if (frame.isRepeatedFrame) "repeated_signal" else "real_frame",
                        "data_size" to frame.imageData.size.toString()
                    )

                    val fileName = if (frame.isRepeatedFrame) {
                        "repeated_signal_${frame.frameSequenceNumber}_${frame.timestamp}.signal"
                    } else {
                        "frame_${frame.frameSequenceNumber}_${frame.timestamp}.jpg"
                    }

                    multipartBuilder.addFormDataPart(
                        "frame_${index}",
                        fileName,
                        frame.imageData.toRequestBody(
                            if (frame.isRepeatedFrame) "application/octet-stream".toMediaType()
                            else "image/jpeg".toMediaType()
                        )
                    )

                    multipartBuilder.addFormDataPart(
                        "frame_${index}_metadata",
                        Json.encodeToString(frameMetadata)
                    )
                }

                val requestBody = multipartBuilder.build()
                val request = Request.Builder()
                    .url("${baseUrl}/upload_batch")
                    .addHeader("X-API-Key", config.apiKey)
                    .post(requestBody)
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    totalFramesSent += frames.size
                    Log.d("SessionDataManager",
                        "Video batch sent successfully. Total frames sent: $totalFramesSent")
                } else {
                    Log.e("SessionDataManager",
                        "Failed to send video batch: ${response.code} - ${response.message}")
                }

                response.close()
            }

            if (navigationEvents.isNotEmpty() || interactionEvents.isNotEmpty()) {
                val breadcrumbBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("type", "breadcrumb_batch")
                    .addFormDataPart("session_id", batch.sessionId)
                    .addFormDataPart("user_id", userId ?: "unknown")
                    .addFormDataPart("user_type", userType)
                    .addFormDataPart("app_version", appVersion)
                    .addFormDataPart("app_info", Json.encodeToString(getAppInfo()))
                    .addFormDataPart("device_info", Json.encodeToString(getDeviceInfo()))
                    .addFormDataPart("batch_number", batch.batchNumber.toString())
                    .addFormDataPart("timestamp", batch.timestamp.toString())

                // Create breadcrumb timeline
                val breadcrumbs = mutableListOf<JsonElement>()

                // Add navigation events with enhanced metadata
                navigationEvents.forEach { nav ->
                    breadcrumbs.add(buildJsonObject {
                        put("type", JsonPrimitive("navigation"))
                        put("timestamp", JsonPrimitive(nav.timestamp))
                        put("sequence", JsonPrimitive(nav.sequenceNumber))
                        put("user_id", JsonPrimitive(userId ?: "unknown"))
                        put("user_type", JsonPrimitive(userType))
                        put("app_version", JsonPrimitive(appVersion))
                        put("data", buildJsonObject {
                            put("from", JsonPrimitive(nav.fromScreen))
                            put("to", JsonPrimitive(nav.toScreen))
                            put("screenType", JsonPrimitive(nav.screenType))
                            put("transitionType", JsonPrimitive(nav.transitionType))
                        })
                    })
                }

                // Add interaction events with enhanced metadata
                interactionEvents.forEach { interaction ->
                    breadcrumbs.add(buildJsonObject {
                        put("type", "interaction")
                        put("timestamp", interaction.timestamp)
                        put("sequence", interaction.sequenceNumber)
                        put("user_id", JsonPrimitive(userId ?: "unknown"))
                        put("user_type", JsonPrimitive(userType))
                        put("app_version", JsonPrimitive(appVersion))

                        if (interaction.rawInteractionData != null) {
                            val rawObject = Json.parseToJsonElement(interaction.rawInteractionData).jsonObject
                            rawObject.forEach { key, value ->
                                if (key == "type") {
                                    put("interaction_type", value)
                                } else {
                                    put(key, value)
                                }
                            }
                        } else {
                            put("interaction_type", JsonPrimitive(interaction.interactionType))
                            put("screen", JsonPrimitive(interaction.screen))
                            put("x", JsonPrimitive(interaction.coordinates.x))
                            put("y", JsonPrimitive(interaction.coordinates.y))
                            put("duration", JsonPrimitive(interaction.duration))
                        }
                    })
                }

                // Sort by sequence number
                breadcrumbs.sortBy { it.jsonObject["sequence"]?.jsonPrimitive?.int }

                breadcrumbBuilder.addFormDataPart(
                    "breadcrumbs",
                    Json.encodeToString(breadcrumbs)
                )

                val breadcrumbBody = breadcrumbBuilder.build()
                val breadcrumbRequest = Request.Builder()
                    .url("${baseUrl}/breadcrumb_batch")
                    .addHeader("X-API-Key", config.apiKey)
                    .post(breadcrumbBody)
                    .build()

                val breadcrumbResponse = okHttpClient.newCall(breadcrumbRequest).execute()

                if (breadcrumbResponse.isSuccessful) {
                    totalNavigationsSent += navigationEvents.size
                    totalInteractionsSent += interactionEvents.size
                    Log.d("SessionDataManager",
                        "Breadcrumb batch sent for user $userId. Navigations: $totalNavigationsSent, " +
                                "Interactions: $totalInteractionsSent")
                } else {
                    Log.e("SessionDataManager",
                        "Failed to send breadcrumb batch: ${breadcrumbResponse.code}")
                }

                breadcrumbResponse.close()
            }

        } catch (e: Exception) {
            Log.e("SessionDataManager", "Error sending batch", e)
        }
    }

    /**
     * Fetch IP location information from the API
     */
    private fun fetchLocationInfo(): LocationInfo? {
        // Check cache first
        val currentTime = System.currentTimeMillis()
        if (cachedLocationInfo != null &&
            (currentTime - locationInfoTimestamp) < LOCATION_CACHE_DURATION_MS) {
            return cachedLocationInfo
        }

        return try {
            val request = Request.Builder()
                .url("http://192.168.0.114:3001/api/v1/ipinfo")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                if (!responseBody.isNullOrEmpty()) {
                    // Configure Json to ignore unknown keys like "readme"
                    val jsonConfig = Json { ignoreUnknownKeys = true }
                    val locationInfo = jsonConfig.decodeFromString<LocationInfo>(responseBody)
                    cachedLocationInfo = locationInfo
                    locationInfoTimestamp = currentTime

                    Log.d("SessionDataManager", "Location info fetched: ${locationInfo.city}, ${locationInfo.region}, ${locationInfo.country}")
                    locationInfo
                } else {
                    null
                }
            } else {
                Log.w("SessionDataManager", "Failed to fetch location info: ${response.code}")
                response.close()
                null
            }
        } catch (e: Exception) {
            Log.e("SessionDataManager", "Error fetching location info", e)
            null
        }
    }

    /**
     * Enhanced device info with IP location information
     */
    private fun getDeviceInfo(): DeviceInfo {
        val displayMetrics = context.resources.displayMetrics

        // Try to get cached location info synchronously, or use null if not available
        val locationInfo = cachedLocationInfo

        // If we don't have cached location info, fetch it asynchronously for next time
        if (locationInfo == null ||
            (System.currentTimeMillis() - locationInfoTimestamp) > LOCATION_CACHE_DURATION_MS) {
            coroutineScope.launch {
                fetchLocationInfo()
            }
        }

        return DeviceInfo(
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            density = displayMetrics.density,
            androidVersion = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            locationInfo = locationInfo
        )
    }

    /**
     * Get comprehensive app information
     */
    private fun getAppInfo(): AppInfo {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            AppInfo(
                version = packageInfo.versionName ?: "unknown",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    packageInfo.versionCode
                },
                packageName = context.packageName,
                buildType = "release",
                compileSdkVersion = Build.VERSION.SDK_INT
            )
        } catch (e: Exception) {
            Log.e("SessionDataManager", "Error getting app info", e)
            AppInfo(
                version = "unknown",
                versionCode = 0,
                packageName = context.packageName,
                buildType = "unknown",
                compileSdkVersion = Build.VERSION.SDK_INT
            )
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager?.getPackageInfo(
                context.packageName, 0
            )?.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
    /**
     * Force flush all buffers
     */
    fun forceFlush() {
        processBatch("force_flush")
    }

    /**
     * Clean up resources
     */
    fun onDestroy() {
        forceFlush()
        coroutineScope.cancel()
    }
}