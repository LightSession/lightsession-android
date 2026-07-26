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
     * How the screen map's wireframes are produced.
     *
     * Scoped to the wireframe shown on the screen graph — *not* to replay frames,
     * which are captured separately and are real screenshots either way.
     *
     * When [captureRealScreens] is on, this is what the graph shows for the first
     * couple of seconds and whenever the real capture does not happen.
     */
    val wireframeMode: WireframeMode = WireframeMode.RECTS,

    /**
     * Whether the screen map upgrades a screen's wireframe to a real screenshot.
     *
     * The wireframe goes out at navigation time. Two and a half seconds later, if the
     * user has neither navigated away nor touched the screen, the SDK captures the
     * screen for real at full resolution and replaces the wireframe with it. The
     * settle delay is the point: it waits for animations, images and network content,
     * so what lands is the screen as the user actually saw it. One capture per screen
     * per install — `CacheManager.isScreenFullyCaptured` stops it repeating.
     *
     * **This stores an unmasked picture of every screen in the app.** Unlike a replay
     * frame, a screen-map capture is permanent and per-screen: it is kept for as long
     * as the project exists, so anything visible when the capture fires — a balance, a
     * document number, a recovery phrase — is in the bucket indefinitely. There is no
     * on-device masking yet; the planned design sends the sensitive rectangles to the
     * server to draw over, which is not built.
     *
     * Turn it off per project if that trade is not the one you want.
     */
    val captureRealScreens: Boolean = true,

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

    /**
     * Where the screen-map wireframe gets drawn.
     *
     * Note what is *not* here: an option to send the real screenshot. The screen map
     * is a permanent, per-screen artefact — one image per screen, kept for as long as
     * the project exists — and there is no on-device masking yet, so offering it would
     * mean offering to store an unmasked picture of every screen in the app forever.
     * That is a decision to make deliberately with masking in hand, not a config flag
     * to leave lying around.
     */
    enum class WireframeMode {
        /**
         * Send the widget rectangles; the server draws the wireframe. The default.
         *
         * Costs the host app a hierarchy walk and about 3 KB, against the 8.25 ms
         * encode and 80 KB that [BITMAP] pays for the same picture (measured on a
         * 1080×2400 frame — see `MaskingCostTest`). It also cannot carry screen
         * content, because there is none in it.
         */
        RECTS,

        /**
         * Draw the wireframe on the device and upload it as a JPEG.
         *
         * What the SDK did before, kept for a backend that does not yet accept
         * `skeleton` in the screen payload — against one of those, [RECTS] would
         * silently produce screens with no image. There is no other reason to
         * choose it.
         */
        BITMAP,
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
