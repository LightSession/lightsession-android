package com.lightsession

/**
 * SDK configuration.
 *
 * [ingestUrl] and [apiUrl] have no defaults on purpose. The previous version
 * hardcoded `http://192.168.0.114:5000` and `http://192.168.0.114:3001` inside
 * [SessionDataManager] and `NetworkDataSender`, with no way to override them —
 * so the artifact published to GitHub Packages as `0.1.11-alpha` could only ever
 * talk to one developer's laptop. A required parameter fails at the call site;
 * a default would fail silently in production.
 */
data class LightSessionConfig(
    val apiKey: String,

    /**
     * Base URL of the ingest service (`ls-ingest`), no trailing slash.
     * Receives `/upload_batch` and `/breadcrumb_batch`.
     */
    val ingestUrl: String,

    /**
     * Base URL of the product API (`ls-api`), no trailing slash.
     * Receives the screen map under `/api/v1/screenmap`.
     */
    val apiUrl: String,

    val enableReplay: Boolean = true,
    val captureQuality: CaptureQuality = CaptureQuality.LOW,

    /**
     * Interval between captures while nothing is happening, milliseconds.
     *
     * One second is the realistic production setting. It was hardcoded to 300ms,
     * which is smooth and is not something to ask of a phone battery for a
     * whole session.
     */
    val captureIntervalMs: Long = 1_000,

    /**
     * Interval while a gesture is in progress, milliseconds.
     *
     * A swipe lasts a few hundred milliseconds. At the idle interval it falls
     * entirely between two frames, so the replay draws its trail over a screen
     * that was never the screen the finger moved across. Sampling densely for
     * the duration of the gesture is what gives the trail something to be
     * synchronised with — and gestures are a fraction of a percent of a
     * session, so it costs almost nothing.
     *
     * Set equal to [captureIntervalMs] to disable bursting.
     */
    val interactionCaptureIntervalMs: Long = 100,

    /**
     * How long the app may sit in the background before the next foreground
     * starts a *new* session, milliseconds.
     *
     * Must match `LS_SESSION__IDLE_TIMEOUT_SECS` on the ingest service, whose
     * default is 30 seconds. The server's reaper finalises a session once it has
     * been idle that long and then forgets it; without rotating here, coming back
     * to the foreground resumes into a session the server has already closed, and
     * the data lands on a row that has to be reconciled after the fact.
     *
     * Matching it is the point. A shorter value here splits sessions the server
     * would have kept whole; a longer one keeps sending to a session it has closed.
     */
    val sessionTimeoutMs: Long = 30_000,

    /**
     * Frames buffered in memory before a flush is triggered, regardless of the
     * timer.
     *
     * The timer alone was not enough once capture became adaptive: a burst runs at
     * [interactionCaptureIntervalMs], so a three-second gesture inside one
     * five-second window put thirty frames into a single multipart request.
     */
    val flushAtFrameCount: Int = 24,

    /**
     * Bytes buffered in memory before a flush is triggered.
     *
     * The bound that actually matters, since frame size varies with
     * [captureQuality] and with how busy the screen is.
     */
    val flushAtBytes: Long = 2L * 1024 * 1024,

    /**
     * Hard ceiling on the in-memory frame buffer.
     *
     * Only reached if the disk write itself is stalling. Past it the oldest frames
     * are discarded so the replay still reaches the end, and interactions are never
     * discarded — see `SessionDataManager`.
     */
    val maxBufferedBytes: Long = 8L * 1024 * 1024,
) {
    enum class CaptureQuality {
        LOW, MEDIUM, HIGH
    }

    init {
        require(apiKey.isNotBlank()) { "apiKey is required" }
        require(captureIntervalMs >= 50) { "captureIntervalMs must be at least 50ms" }
        require(interactionCaptureIntervalMs in 16..captureIntervalMs) {
            "interactionCaptureIntervalMs must be between 16ms and captureIntervalMs"
        }
        require(ingestUrl.isNotBlank()) { "ingestUrl is required" }
        require(apiUrl.isNotBlank()) { "apiUrl is required" }
        require(sessionTimeoutMs >= 5_000) { "sessionTimeoutMs must be at least 5s" }
        require(flushAtFrameCount >= 1) { "flushAtFrameCount must be positive" }
        require(flushAtBytes >= 64 * 1024) { "flushAtBytes must be at least 64 KB" }
        require(maxBufferedBytes > flushAtBytes) {
            "maxBufferedBytes must exceed flushAtBytes, or every flush would already be over the cap"
        }
    }

    internal val normalizedIngestUrl: String get() = ingestUrl.trimEnd('/')
    internal val normalizedApiUrl: String get() = apiUrl.trimEnd('/')
}
