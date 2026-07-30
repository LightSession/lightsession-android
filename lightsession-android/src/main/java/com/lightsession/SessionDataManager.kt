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
import kotlinx.serialization.json.jsonArray
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
    /**
     * Mutable, and that is the fix.
     *
     * This used to be a `val` set once per process, so an app backgrounded for an
     * hour resumed into a session the server's reaper had already finalised and
     * forgotten — a second life landing on a row that described the first. See
     * [rotateIfIdle].
     *
     * `@Volatile` because it is read on the capture thread and written on the main
     * thread when the app returns to the foreground.
     */
    @Volatile
    private var sessionId = newSessionId()

    private var sessionStartTime = System.currentTimeMillis()

    /** When the app last went to background, for [rotateIfIdle]. */
    private val backgroundedAt = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Bytes of frame data sitting in [frameBuffer].
     *
     * Tracked rather than measured because measuring means walking the queue and
     * summing `imageData.size` on every push, from the capture thread.
     */
    private val bufferedBytes = java.util.concurrent.atomic.AtomicLong(0)

    /** Frames discarded because the buffer hit its ceiling. */
    private val framesShed = AtomicInteger(0)

    /**
     * Frames sitting in [frameBuffer].
     *
     * Counted rather than asked for. `ConcurrentLinkedQueue.size` is O(n) — it walks the
     * whole queue — and it was being read on every single frame, which is the exact cost
     * [bufferedBytes] exists to avoid and says so a few lines up. With a full buffer that
     * walk ran on the capture thread thousands of times a second's worth of frames.
     *
     * A shadow counter can drift from the thing it counts, so it is maintained at exactly
     * the three places [bufferedBytes] is and nowhere else — the offer, the shed, and the
     * drain are the only operations that change the queue's contents.
     */
    private val bufferedFrames = AtomicInteger(0)


    /**
     * Batches on their way out, on disk.
     *
     * A batch is written here before any upload is attempted and removed only on a
     * 2xx. Before this, `processBatch` drained the in-memory queues and then fired
     * an upload; a failed request logged a line and the data was already gone, and
     * a killed process took everything buffered with it. See [BatchSpool].
     *
     * The size cap lives there too. There used to be a `MAX_BATCH_SIZE_MB = 50`
     * constant here that nothing read.
     */
    private val spool = BatchSpool(context.filesDir)

    /**
     * Guards the uploader. Without it the 5-second tick, a lifecycle flush and a
     * low-memory flush can each start a drain, and three drains would upload the
     * same spooled entries concurrently.
     */
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Serialises [processBatch]: two concurrent callers would each take half of
     *  every queue and produce two batches with interleaved sequence holes. */
    private val batchLock = Any()

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

    /**
     * Identify crumbs, already in wire form.
     *
     * Kept as built JSON rather than as a typed event because nothing here inspects it —
     * `spoolCrumbs` merges it into the breadcrumb array untouched, so a data class would be a
     * shape to maintain in two places for no reader.
     */
    private val identifyBuffer = ConcurrentLinkedQueue<JsonElement>()

    // Sequence tracking
    private val globalSequenceCounter = AtomicInteger(0)
    private val frameSequenceCounter = AtomicInteger(0)
    private val baseUrl: String = config.normalizedIngestUrl


    // Statistics
    /** Atomic because `processBatch` runs from the ticker, from a lifecycle
     *  callback and from a low-memory callback. `++` on an Int gave two batches the
     *  same number. */
    private val batchCounter = AtomicInteger(0)
    private val totalFramesSent = AtomicInteger(0)
    private val totalNavigationsSent = AtomicInteger(0)
    private val totalInteractionsSent = AtomicInteger(0)
    private val totalBatchesDropped = AtomicInteger(0)

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
        val screenId: String? = null,
        val coordinates: Coordinates,
        val duration: Long? = null,
        val path: List<Coordinates>? = null, // For swipe gestures
        val rawInteractionData: String? = null // Store the original JSON for breadcrumb
    ) : SessionEvent()

    @Serializable
    data class Coordinates(val x: Float, val y: Float)

    // `SessionBatch`, `BatchMetadata` and `UserInfo` lived here and were never
    // constructed anywhere in the SDK. The wire format is built by `spoolFrames` and
    // `spoolCrumbs` out of plain maps, so these described a payload that had stopped
    // existing — including a `timeSinceSessionStart` nothing computed and a `batchSizeBytes`
    // nothing measured.

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

    /**
     * Set at [init]. Read on every batch rather than copied, so an `identify` partway through
     * a session takes effect on the next batch instead of the next launch.
     */
    private lateinit var identity: Identity

    private val userId: String get() = if (::identity.isInitialized) identity.effectiveId else "unknown"
    private val userType: String get() = if (::identity.isInitialized) identity.userType else "anonymous"
    private var appVersion: String = getAppVersion(context)

    /**
     * The last location lookup, and when it happened.
     *
     * `@Volatile` because the write happens on an IO coroutine and the read happens
     * wherever `getDeviceInfo` is called from — the batch ticker, a lifecycle callback, a
     * low-memory callback. Without it a reader could see the timestamp updated but not the
     * value, and refetch on every batch forever.
     */
    @Volatile
    private var cachedLocationInfo: LocationInfo? = null

    @Volatile
    private var locationInfoTimestamp: Long = 0

    /**
     * Guards against piling up lookups.
     *
     * `getDeviceInfo` launched a fetch whenever the cache looked stale, and it runs once
     * per batch — so against a slow or unreachable endpoint a new request went out every
     * five seconds while none of them had come back yet. One in flight at a time.
     */
    private val locationFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Initialize the session manager with user context
     */
    internal fun init(identity: Identity) {
        this.identity = identity
        this.appVersion = getAppVersion(context)

        sessionStartTime = System.currentTimeMillis()

        // Fetch location info on initialization
        if (config.collectLocation) {
            coroutineScope.launch {
                fetchLocationInfo()
            }
        }

        startBatchProcessor()

        // Anything the previous process left pending. This is the reason the spool
        // exists: a session that ended with the app being swiped away used to lose
        // its last batch entirely.
        coroutineScope.launch {
            spool.prune()
            val pending = spool.pendingCount()
            if (pending > 0) {
                Log.i("SessionDataManager",
                    "recovered $pending batch(es) from the previous run (${spool.sizeBytes() / 1024} KB)")
            }
            drainSpool()
        }

        Log.d("SessionDataManager", "Initialized session: $sessionId (${this.userType})")
    }

    /**
     * Add a frame from video capture
     */
    fun addFrame(
        imageData: ByteArray,
        isRepeatedFrame: Boolean,
        currentScreen: String? = null
    ) {
        // The backstop. Every producer checks `Recording` where the work happens, to avoid
        // doing it; this is the only place all three kinds of recorded data converge, so
        // checking again is what makes a producer that forgets cost a wasted capture rather
        // than a leak.
        if (!Recording.enabled) return

        val frame = FrameData(
            timestamp = System.currentTimeMillis(),
            sequenceNumber = globalSequenceCounter.incrementAndGet(),
            frameSequenceNumber = frameSequenceCounter.incrementAndGet(),
            imageData = imageData,
            isRepeatedFrame = isRepeatedFrame,
            currentScreen = currentScreen
        )

        frameBuffer.offer(frame)
        val buffered = bufferedBytes.addAndGet(imageData.size.toLong())
        val frames = bufferedFrames.incrementAndGet()

        // No per-frame line here. It ran three to ten times a second, and a `Log.d`
        // argument list is built whether or not the line survives — so it was string
        // concatenation on the capture thread, in release, for output nobody reads. The
        // batch line reports the counts, which is the granularity anyone debugging this
        // actually works at.

        if (buffered > config.maxBufferedBytes) {
            shedOldestFrames()
        } else if (frames >= config.flushAtFrameCount || buffered >= config.flushAtBytes) {
            // Off the capture thread: spooling writes files, and the capture thread
            // is the one that has to be ready for the next frame.
            requestFlush(if (buffered >= config.flushAtBytes) "size" else "count")
        }
    }

    /**
     * Discards the oldest buffered frames until the buffer is back under its
     * ceiling.
     *
     * Only reachable when the disk write itself is stalling — the count and size
     * triggers normally keep the buffer far below this. Something has to go at that
     * point, and the choice is deliberate: dropping the *oldest* leaves a gap in the
     * middle of the replay but lets it reach the end, and the end is where the
     * question "why did they give up" is answered. Dropping the newest would give a
     * continuous replay that stops before the interesting part.
     *
     * Interactions and navigations are never shed. A tap is a few hundred bytes and
     * is the only record that it happened; a frame is tens of kilobytes and the one
     * beside it looks almost identical.
     */
    private fun shedOldestFrames() {
        val shed = shedToFit(frameBuffer, bufferedBytes, config.flushAtBytes)
        if (shed > 0) {
            bufferedFrames.addAndGet(-shed)
            val total = framesShed.addAndGet(shed)
            // Loud, because silent truncation reads as "the recording was always
            // like that" rather than "frames were discarded here".
            Log.w("SessionDataManager",
                "in-memory buffer hit ${config.maxBufferedBytes / 1024} KB; " +
                        "shed $shed oldest frame(s), $total this session — " +
                        "the spool is not draining")
        }
        requestFlush("buffer_full")
    }

    internal companion object {
        /** How often the buffers are drained to disk when nothing else forces it. */
        private const val BATCH_INTERVAL_MS = 5_000L

        /** How long a location lookup is reused before it is worth asking again. */
        private const val LOCATION_CACHE_DURATION_MS = 30 * 60 * 1000L

        /**
         * Drops from the head of `queue` until `bytes` is at or below `target`.
         *
         * Head-first is the decision worth pinning: the queue is FIFO, so this
         * discards the *oldest* frames and leaves the newest. A replay then has a
         * gap in the middle but reaches the end, and the end is where "why did they
         * give up" is answered. Taking from the tail would give a continuous replay
         * that stops before the interesting part.
         *
         * Returns how many were dropped.
         */
        internal fun shedToFit(
            queue: ConcurrentLinkedQueue<FrameData>,
            bytes: java.util.concurrent.atomic.AtomicLong,
            target: Long
        ): Int {
            var shed = 0
            while (bytes.get() > target) {
                val frame = queue.poll() ?: break
                bytes.addAndGet(-frame.imageData.size.toLong())
                shed++
            }
            return shed
        }
    }

    /** Spools on the IO scope. For callers that must not block, which is all of
     *  them except the lifecycle path. */
    private fun requestFlush(reason: String) {
        coroutineScope.launch {
            processBatch(reason)
            drainSpool()
        }
    }

    /**
     * Starts a new session if the app was away longer than the server keeps one
     * open.
     *
     * Called when the app returns to the foreground. The buffers are flushed first
     * so nothing recorded under the old id is attributed to the new one — spooled
     * batches carry their session id in the payload, so anything already on disk is
     * unaffected either way.
     */
    fun rotateIfIdle() {
        val away = backgroundedAt.get()
        if (away == 0L) return
        val idleFor = System.currentTimeMillis() - away
        if (idleFor < config.sessionTimeoutMs) return

        processBatch("session_end", deferFrames = true)

        val previous = sessionId
        sessionId = newSessionId()
        sessionStartTime = System.currentTimeMillis()
        // Restarted so each session's events are numbered from one. Safe because
        // the buffers were just drained, and because anything already spooled
        // carries the old id.
        globalSequenceCounter.set(0)
        frameSequenceCounter.set(0)
        batchCounter.set(0)
        backgroundedAt.set(0)

        Log.i("SessionDataManager",
            "idle ${idleFor / 1000}s exceeded the ${config.sessionTimeoutMs / 1000}s session " +
                    "timeout; rotated $previous -> $sessionId")
    }

    /**
     * Ends the current session and starts another, whatever the idle timer says.
     *
     * For sign-out. [rotateIfIdle] exists for the app coming back after long enough that the
     * server has already closed the session; this is the other reason a session ends — the
     * person changed. Keeping one session across that would produce a replay of two people
     * taking turns.
     *
     * Buffers are flushed first, so nothing recorded under the old identity is attributed to
     * the new one. Anything already spooled carries its own session id and is unaffected.
     */
    fun startNewSession(reason: String) {
        processBatch(reason, deferFrames = true)

        val previous = sessionId
        sessionId = newSessionId()
        sessionStartTime = System.currentTimeMillis()
        globalSequenceCounter.set(0)
        frameSequenceCounter.set(0)
        batchCounter.set(0)
        backgroundedAt.set(0)

        Log.i("SessionDataManager", "session rotated ($reason): $previous -> $sessionId")
    }

    /** Records the moment the app went away, for [rotateIfIdle]. */
    fun markBackgrounded() {
        backgroundedAt.set(System.currentTimeMillis())
    }

    /**
     * Random, not the wall clock.
     *
     * The clock version collided whenever two processes started in the same
     * millisecond, and — worse — was predictable, so one installation could guess
     * another's session ids.
     */
    private fun newSessionId(): String = UUID.randomUUID().toString()

    /**
     * Records that the person said who they are.
     *
     * Queued as a breadcrumb rather than posted on its own, so it inherits the spool, the
     * retry and the ordering that already exist. Identify is the crumb that can least afford
     * to be lost — everything after it is attributed to the wrong person if it is — and a
     * dedicated endpoint would have to reimplement all three.
     *
     * Carries both ids. The user id says who; the anonymous id says which device, which is
     * what lets the server attribute what this install did *before* this moment.
     */
    fun addIdentify(userId: String, anonymousId: String, traits: Map<String, Any?>) {
        val crumb = buildJsonObject {
            put("type", JsonPrimitive("identify"))
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
            put("sequence", JsonPrimitive(globalSequenceCounter.incrementAndGet()))
            put("user_id", JsonPrimitive(userId))
            put("anonymous_id", JsonPrimitive(anonymousId))
            put("app_version", JsonPrimitive(appVersion))
            put("traits", encodeTraits(traits))
        }
        identifyBuffer.offer(crumb)
        Log.d("SessionDataManager", "identify queued")
        requestFlush("identify")
    }

    /**
     * Traits as JSON, keeping only what JSON can carry.
     *
     * Anything else is dropped with a warning rather than stringified. `toString()` on a
     * domain object produces `com.acme.User@3f2a1b`, which would be stored, indexed and
     * displayed as if it meant something.
     */
    private fun encodeTraits(traits: Map<String, Any?>): JsonElement = buildJsonObject {
        for ((key, value) in traits) {
            when (value) {
                null -> {}
                is String -> put(key, JsonPrimitive(value))
                is Number -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                else -> Log.w(
                    "SessionDataManager",
                    "trait '$key' is a ${value.javaClass.simpleName} and was dropped; " +
                        "send a string, number or boolean",
                )
            }
        }
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
        if (!Recording.enabled) return

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
        if (!Recording.enabled) return
        try {
            val jsonObject = JSONObject(interactionData)
            val type = jsonObject.getString("type")
            val startTime = jsonObject.getLong("start_time")
            val endTime = jsonObject.getLong("end_time")
            val pointsArray = jsonObject.getJSONArray("points")
            // Not `optString`. Android's version returns the *string* "null" when the key
            // is present with a JSON null — the fallback only applies to a missing key — so
            // a screen id of "null" was reaching the server and being stored as one.
            val screenId = jsonObject.opt("screen_id")
                ?.takeUnless { it === JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotBlank() }

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
                screenId = screenId,
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
                // Every tick also retries whatever is still on disk, so a batch
                // that failed during a dead spot goes out as soon as there is
                // signal again rather than waiting for new data to push it.
                drainSpool()
            }
        }
    }

    /**
     * Drains the in-memory buffers onto disk. Does no network work.
     *
     * That split is the whole point. Whoever calls this — the ticker, a lifecycle
     * callback, `onDestroy` — only has to survive long enough to write a file, and
     * the upload becomes somebody else's problem. Before, a flush was only as good
     * as the request it fired: `onDestroy` called `forceFlush()` and then
     * `coroutineScope.cancel()`, which cancelled the upload it had just started.
     */
    private fun processBatch(
        reason: String,
        /**
         * Whether the frame files may be written after this returns.
         *
         * The draining is always synchronous and is what frees the memory; the writing is
         * what costs. Set from a main-thread caller — a lifecycle or low-memory callback —
         * because `spoolFrames` creates two files per frame, and a full buffer is 8MB of
         * JPEG across a couple of hundred frames. That is hundreds of file creates on the
         * thread drawing the UI, at the exact moment the app is being backgrounded.
         *
         * The cost of deferring is durability: if the process is killed between here and
         * the write, those frames are gone. Two things make that the right trade. The
         * coroutine scope is not cancelled until `onDestroy`, so a write launched at
         * `onStop` has seconds in which the process is nearly always still alive. And
         * frames are the data this class already discards on purpose when it has to — the
         * buffer sheds them, the spool evicts them — precisely because the one beside a
         * lost frame looks almost identical, while an interaction is the only record that
         * it happened.
         *
         * Breadcrumbs are never deferred, for the same reason.
         */
        deferFrames: Boolean = false,
    ) = synchronized(batchLock) {
        val frames = drain(frameBuffer)
        bufferedBytes.addAndGet(-frames.sumOf { it.imageData.size.toLong() })
        bufferedFrames.addAndGet(-frames.size)
        val navigations = drain(navigationBuffer)
        val interactions = drain(interactionBuffer)
        val identifies = drain(identifyBuffer)

        if (frames.isEmpty() && navigations.isEmpty() && interactions.isEmpty() &&
            identifies.isEmpty()
        ) {
            return@synchronized
        }

        val batchNumber = batchCounter.incrementAndGet()
        val batchId = "${sessionId}_${System.currentTimeMillis()}"
        // Once per batch. This was called twice — once for the batch metadata and
        // once for the breadcrumb form — and each call could launch a location
        // fetch.
        val deviceInfo = getDeviceInfo()

        if (frames.isNotEmpty()) {
            // Outside `batchLock` when deferred, which is safe: the queues have already
            // been drained into `frames`, and the spool has a lock of its own.
            if (deferFrames) {
                coroutineScope.launch { spoolFrames(batchId, batchNumber, reason, frames) }
            } else {
                spoolFrames(batchId, batchNumber, reason, frames)
            }
        }
        if (navigations.isNotEmpty() || interactions.isNotEmpty() || identifies.isNotEmpty()) {
            spoolCrumbs(batchId, batchNumber, deviceInfo, navigations, interactions, identifies)
        }

        // The spool's own totals are deliberately not in here. `pendingCount()` lists two
        // directories and `sizeBytes()` walks the whole tree recursively, and a `Log.d`
        // argument list is evaluated whether or not the line is ever printed — so every
        // batch paid for two directory walks to build a string that release builds discard.
        // This can also run on the main thread, through `forceFlush` from a lifecycle
        // callback. `getStats()` reports those numbers when something actually asks.
        Log.d("SessionDataManager",
            "Batch #$batchNumber spooled: ${frames.size} frames, ${navigations.size} navigations, " +
                    "${interactions.size} interactions (reason: $reason)")
    }

    private fun <T> drain(queue: ConcurrentLinkedQueue<T>): List<T> {
        val out = mutableListOf<T>()
        while (true) out.add(queue.poll() ?: break)
        return out
    }

    /**
     * Writes a frame batch to the spool in the exact shape `/upload_batch` expects.
     *
     * The metadata is serialised here rather than at upload time so a batch
     * recovered from a previous run still describes itself — including its
     * `flush_reason`, which used to be hardcoded to `"scheduled"` even when the
     * flush was forced.
     */
    private fun spoolFrames(
        batchId: String,
        batchNumber: Int,
        reason: String,
        frames: List<FrameData>
    ) {
        val batchMetadata = mapOf(
            "batch_id" to batchId,
            "session_id" to sessionId,
            "user_id" to userId,
            "user_type" to userType,
            "app_version" to appVersion,
            "total_frame_count" to frames.size.toString(),
            "real_frame_count" to frames.count { !it.isRepeatedFrame }.toString(),
            "repeated_signal_count" to frames.count { it.isRepeatedFrame }.toString(),
            "flush_reason" to reason,
            "total_batches_sent" to batchNumber.toString(),
            "first_frame_timestamp" to frames.first().timestamp.toString(),
            "last_frame_timestamp" to frames.last().timestamp.toString(),
            "sequence_range" to
                "${frames.first().frameSequenceNumber}-${frames.last().frameSequenceNumber}"
        )

        val spooled = mutableListOf<BatchSpool.SpooledFrame>()
        val perFrameMetadata = mutableListOf<String>()

        frames.forEachIndexed { index, frame ->
            val fileName = if (frame.isRepeatedFrame) {
                "repeated_signal_${frame.frameSequenceNumber}_${frame.timestamp}.signal"
            } else {
                "frame_${frame.frameSequenceNumber}_${frame.timestamp}.jpg"
            }
            spooled.add(
                BatchSpool.SpooledFrame(
                    fileName = fileName,
                    bytes = frame.imageData,
                    isRepeated = frame.isRepeatedFrame
                )
            )
            perFrameMetadata.add(
                Json.encodeToString(
                    mapOf(
                        "timestamp" to frame.timestamp.toString(),
                        "sequence_number" to frame.frameSequenceNumber.toString(),
                        "frame_index" to index.toString(),
                        "is_repeated_frame" to frame.isRepeatedFrame.toString(),
                        "frame_type" to
                            if (frame.isRepeatedFrame) "repeated_signal" else "real_frame",
                        "data_size" to frame.imageData.size.toString()
                    )
                )
            )
        }

        if (spool.writeFrames(
                batchId = batchId,
                metadataJson = Json.encodeToString(batchMetadata),
                frameMetadataJson = perFrameMetadata,
                frames = spooled
            ) == null
        ) {
            totalBatchesDropped.incrementAndGet()
        }
    }

    /** Writes a breadcrumb batch to the spool in the shape `/breadcrumb_batch` expects. */
    private fun spoolCrumbs(
        batchId: String,
        batchNumber: Int,
        deviceInfo: DeviceInfo,
        navigations: List<NavigationEvent>,
        interactions: List<InteractionEvent>,
        identifies: List<JsonElement>,
    ) {
        val breadcrumbs = mutableListOf<JsonElement>()
        breadcrumbs.addAll(identifies)

        navigations.forEach { nav ->
            breadcrumbs.add(buildJsonObject {
                put("type", JsonPrimitive("navigation"))
                put("timestamp", JsonPrimitive(nav.timestamp))
                put("sequence", JsonPrimitive(nav.sequenceNumber))
                put("user_id", JsonPrimitive(userId))
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

        interactions.forEach { interaction ->
            breadcrumbs.add(buildJsonObject {
                put("type", "interaction")
                put("timestamp", interaction.timestamp)
                put("sequence", interaction.sequenceNumber)
                put("user_id", JsonPrimitive(userId))
                put("user_type", JsonPrimitive(userType))
                put("app_version", JsonPrimitive(appVersion))
                interaction.screenId?.let { sid -> put("screen_id", JsonPrimitive(sid)) }

                if (interaction.rawInteractionData != null) {
                    val rawObject = Json.parseToJsonElement(interaction.rawInteractionData).jsonObject
                    rawObject.forEach { key, value ->
                        if (key == "type") put("interaction_type", value) else put(key, value)
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

        breadcrumbs.sortBy { it.jsonObject["sequence"]?.jsonPrimitive?.int }

        val fields = mapOf(
            "type" to "breadcrumb_batch",
            "session_id" to sessionId,
            "user_id" to userId,
            "user_type" to userType,
            "app_version" to appVersion,
            "app_info" to Json.encodeToString(getAppInfo()),
            "device_info" to Json.encodeToString(deviceInfo),
            "batch_number" to batchNumber.toString(),
            "timestamp" to System.currentTimeMillis().toString(),
            "breadcrumbs" to Json.encodeToString(breadcrumbs)
        )

        if (spool.writeCrumbs(batchId, fields) == null) {
            totalBatchesDropped.incrementAndGet()
        }
    }

    /**
     * Uploads everything on disk, oldest first, removing each entry only once the
     * server has accepted it.
     *
     * Breadcrumbs go before frames. They are two orders of magnitude smaller and
     * they are the only record that an interaction happened, so a slow or timing-out
     * frame upload must not sit in front of them.
     *
     * Single-flight: the ticker, a background flush and a low-memory flush can all
     * ask for a drain, and three of them running at once would upload the same
     * entries three times.
     */
    private fun drainSpool() {
        if (!draining.compareAndSet(false, true)) return
        try {
            for (entry in spool.pendingCrumbs()) {
                if (!uploadCrumbs(entry)) break
            }
            for (entry in spool.pendingFrames()) {
                if (!uploadFrames(entry)) break
            }
        } catch (e: Exception) {
            Log.e("SessionDataManager", "spool drain failed", e)
        } finally {
            draining.set(false)
        }
    }

    /**
     * Returns false when the drain should stop for now — a network failure means
     * the next entry would fail too, and hammering a dead connection wastes battery
     * and radio.
     */
    private fun uploadCrumbs(entry: BatchSpool.CrumbEntry): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        entry.fields.forEach { (name, value) -> builder.addFormDataPart(name, value) }

        val request = Request.Builder()
            .url("${baseUrl}/breadcrumb_batch")
            .addHeader("X-API-Key", config.apiKey)
            .post(builder.build())
            .build()

        return send(request, entry.file, "breadcrumb ${entry.file.name}") {
            // Counted by reading the payload back rather than by carrying extra
            // form fields the server would have to ignore.
            val crumbs = entry.fields["breadcrumbs"]
                ?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
                ?: return@send
            var navigations = 0
            var interactions = 0
            for (crumb in crumbs) {
                when (crumb.jsonObject["type"]?.jsonPrimitive?.content) {
                    "navigation" -> navigations++
                    "interaction" -> interactions++
                }
            }
            totalNavigationsSent.addAndGet(navigations)
            totalInteractionsSent.addAndGet(interactions)
        }
    }

    private fun uploadFrames(entry: BatchSpool.FrameEntry): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("type", "frame_batch")
            .addFormDataPart("metadata", entry.metadataJson)

        entry.frames.forEachIndexed { index, frame ->
            builder.addFormDataPart(
                "frame_${index}",
                frame.fileName,
                frame.bytes.toRequestBody(
                    if (frame.isRepeated) "application/octet-stream".toMediaType()
                    else "image/jpeg".toMediaType()
                )
            )
            // Recovered from disk beside the frame, so a batch written by an older
            // run still uploads with the metadata it was created with.
            spool.frameMetadata(entry.dir, frame.fileName)?.let { meta ->
                builder.addFormDataPart("frame_${index}_metadata", meta)
            }
        }

        val request = Request.Builder()
            .url("${baseUrl}/upload_batch")
            .addHeader("X-API-Key", config.apiKey)
            .post(builder.build())
            .build()

        return send(request, entry.dir, "frames ${entry.dir.name} (${entry.frames.size})") {
            totalFramesSent.addAndGet(entry.frames.size)
        }
    }

    /**
     * Sends one request and settles the spool entry by the answer.
     *
     * The three outcomes are deliberately different. 2xx removes the entry. A 4xx
     * other than 408/429 means the server will never accept it, so retrying is
     * pointless and the entry is dropped with a loud log — otherwise it would sit in
     * the spool forever, taking budget from data that *can* be delivered. Anything
     * else counts as a failure and is retried, up to the spool's attempt cap.
     *
     * Returns false to stop the drain: a transport exception means the network is
     * gone, not that this particular entry is bad.
     */
    private fun send(
        request: Request,
        entry: java.io.File,
        label: String,
        onAccepted: () -> Unit
    ): Boolean {
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        spool.acknowledge(entry)
                        onAccepted()
                        Log.d("SessionDataManager", "sent $label")
                        true
                    }
                    response.code in 400..499 && response.code != 408 && response.code != 429 -> {
                        Log.e("SessionDataManager",
                            "server rejected $label with ${response.code}; dropping it")
                        spool.acknowledge(entry)
                        totalBatchesDropped.incrementAndGet()
                        true
                    }
                    else -> {
                        Log.w("SessionDataManager",
                            "retrying $label after ${response.code}")
                        spool.recordFailure(entry)
                        false
                    }
                }
            }
        } catch (e: Exception) {
            // Offline. The entry stays on disk; this is the case the spool exists
            // for, so it is a debug line rather than an error.
            Log.d("SessionDataManager", "no connectivity for $label: ${e.message}")
            spool.recordFailure(entry)
            false
        }
    }

    /**
     * Fetch IP location information from the API
     */
    private fun fetchLocationInfo(): LocationInfo? {
        // Belt as well as braces. Both call sites check, and this is the one place the
        // request is actually made — a future caller that forgot would otherwise be the
        // whole of the failure.
        if (!config.collectLocation) return null

        // Check cache first
        val currentTime = System.currentTimeMillis()
        if (cachedLocationInfo != null &&
            (currentTime - locationInfoTimestamp) < LOCATION_CACHE_DURATION_MS) {
            return cachedLocationInfo
        }

        if (!locationFetchInFlight.compareAndSet(false, true)) return cachedLocationInfo

        return try {
            val request = Request.Builder()
                .url("${config.normalizedApiUrl}/api/v1/ipinfo")
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

                    // Deliberately without the values. This is a host app's logcat, and
                    // where its user is sitting is not something to print into it — a log
                    // is readable by any app with the permission on older devices, and by
                    // anyone holding the phone with adb.
                    Log.d("SessionDataManager", "location resolved")
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
        } finally {
            locationFetchInFlight.set(false)
        }
    }

    /**
     * Enhanced device info with IP location information
     */
    private fun getDeviceInfo(): DeviceInfo {

        // Try to get cached location info synchronously, or use null if not available
        val locationInfo = cachedLocationInfo

        // If we don't have cached location info, fetch it asynchronously for next time
        if (config.collectLocation &&
            (locationInfo == null ||
                (System.currentTimeMillis() - locationInfoTimestamp) > LOCATION_CACHE_DURATION_MS)
        ) {
            coroutineScope.launch {
                fetchLocationInfo()
            }
        }

        val screen = ScreenGeometry.size()

        return DeviceInfo(
            // The server divides raw touch pixels by these to get the 0..1 coordinates every
            // heatmap is built from, so they have to describe the image the heat is drawn on.
            // They used to come from the app context, which reports the Activity's area — 2337
            // of a 2400-pixel display — while the capture is the display. See [ScreenGeometry].
            screenWidth = screen.width,
            screenHeight = screen.height,
            density = ScreenGeometry.density,
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
                // Read from the app rather than asserted. This was the literal "release",
                // so a debug build reported itself as a release one and the field could
                // never be used to tell test traffic from real.
                buildType = if (isDebuggable()) "debug" else "release",
                // The device's API level, which is what this actually is. It was labelled
                // `compileSdkVersion`, a build-time constant the SDK cannot see from here
                // and which has nothing to do with the phone the session came from.
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

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
     * Writes whatever is buffered to disk, then asks for an upload.
     *
     * No network work either way, so this never blocks on a request. What it does block
     * on depends on [deferFrames]: with it unset the whole batch is on disk before this
     * returns, which is what `onDestroy` needs, since the scope it would otherwise defer
     * to is cancelled on the next line. With it set, only the breadcrumbs are — see
     * [processBatch].
     */
    fun forceFlush(reason: String = "force_flush", deferFrames: Boolean = false) {
        processBatch(reason, deferFrames)
        coroutineScope.launch { drainSpool() }
    }

    /**
     * Clean up resources. Nothing calls this, and nothing reliably can.
     *
     * Android offers no "the process is about to die" callback: `Application.onTerminate`
     * runs on emulators only, and a swipe-away or a low-memory kill gives no notice at all.
     * That absence is the whole reason [BatchSpool] exists and the reason
     * [FlushTriggers.onStop] is the flush that matters — it is the last moment the process
     * is guaranteed to be alive.
     *
     * Kept because a host that does have a definite end — a test, a tool, an app tearing the
     * SDK down deliberately — has somewhere to call. The ordering below is load-bearing for
     * that case: the flush is synchronous by default, so the batch is on disk before the
     * scope is cancelled, and cancelling then abandons only the upload *attempt*. It also
     * means no deferred frame write can be cancelled out from under itself, since a
     * deferring caller never passes through here.
     */
    fun onDestroy() {
        forceFlush("destroy")
        coroutineScope.cancel()
        Log.d("SessionDataManager",
            "destroyed; ${spool.pendingCount()} batch(es) left on disk for the next run")
    }

    /**
     * Tries the spool again without touching the in-memory buffers.
     *
     * For coming back to the foreground: there is nothing new to flush, but there
     * may well be entries that failed while the app was away or in a previous
     * process.
     */
    fun retryPending() {
        if (spool.pendingCount() == 0) return
        coroutineScope.launch { drainSpool() }
    }

    /** Counters, for the sample app and for debugging a device in hand. */
    fun getStats(): Map<String, Any> = mapOf(
        "sessionId" to sessionId,
        "batchesSpooled" to batchCounter.get(),
        "framesSent" to totalFramesSent.get(),
        "interactionsSent" to totalInteractionsSent.get(),
        "navigationsSent" to totalNavigationsSent.get(),
        "batchesDropped" to totalBatchesDropped.get(),
        "spoolPending" to spool.pendingCount(),
        "spoolBytes" to spool.sizeBytes()
    )
}