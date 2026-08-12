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
 *
 * ## Calling this from Java
 *
 * Every parameter after [apiUrl] has a default, and Java cannot use a Kotlin default — so without
 * [JvmOverloads] a Java caller has to pass all twenty-one, in order. That is not a hypothetical
 * inconvenience: the sample app did exactly that, and each field added since moved every argument
 * after it onto a different parameter. It broke on `wireframeMode` receiving a millisecond count.
 *
 * The generated overloads drop parameters from the end, so `new LightSessionConfig(key, ingest, api)`
 * works and picking up a later field still means passing everything before it. Java callers who need
 * one of the tail options should pass them and stop there; Kotlin callers should use named arguments
 * and ignore all of this.
 */
data class LightSessionConfig @JvmOverloads constructor(
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
     * Cover text and input fields before a captured screen leaves the device.
     *
     * Applies to **every** capture that contains real pixels: replay frames and, when
     * [captureRealScreens] is on, the screen map's screenshots. Wireframes are
     * unaffected — there is no content in a rectangle to cover.
     *
     * On by default. Text is where the sensitive content lives, and a privacy default
     * should fail towards covering rather than towards leaking. See [Masking] for why
     * this happens on the device rather than on the server, and what it costs.
     */
    val maskText: Boolean = true,

    /**
     * Cover images before a captured screen leaves the device.
     *
     * Off by default: this covers every icon and logo along with the photos, which
     * leaves a replay saying very little about what the user did. Worth turning on for
     * an app that displays documents, receipts or user uploads.
     */
    val maskImages: Boolean = false,

    /**
     * Draw masks translucent with a red border instead of opaque.
     *
     * For verifying *placement* during integration — a misplaced mask still looks like a
     * mask while leaving the content readable beside it. Never ship this on.
     */
    val maskDebugHighlight: Boolean = false,

    /**
     * Whether the screen map upgrades a screen's wireframe to a real screenshot.
     *
     * The wireframe goes out at navigation time. Five and a half seconds later, if the
     * user has neither navigated away nor touched the screen, the SDK captures the
     * screen for real at full resolution and replaces the wireframe with it. The
     * settle delay is the point: it waits for animations, images and network content,
     * so what lands is the screen as the user actually saw it. One capture per screen
     * per install — `CacheManager.isScreenFullyCaptured` stops it repeating.
     *
     * A screen-map capture is permanent and per-screen — kept for as long as the
     * project exists — so it is the capture where masking matters most. [maskText] is
     * on by default and applies here; what survives to the bucket is the screen with
     * its text covered. Turning masking off while leaving this on means storing an
     * unmasked picture of every screen in the app indefinitely.
     */
    val captureRealScreens: Boolean = true,

    /**
     * Whether the host names its own screens, instead of the SDK discovering them.
     *
     * Off for a normal Android app, where the platform *is* the answer: an Activity resumes, a
     * fragment destination changes, a Compose NavController reports one.
     *
     * On for React Native, Flutter, Compose Multiplatform — anything where one Activity hosts every
     * screen. With this off such an app gets a node named after that Activity at the top of every
     * session: a screen no user ever navigated to, permanently, since screens are permanent. With it
     * on the Activity is not reported and `setScreen` is the only source, which is the truth for such
     * an app.
     *
     * Forgetting it is no longer fatal. An Activity that [LightSession.setScreen] names while it is
     * in front stops reporting itself from then on, so the map comes out right either way and the
     * SDK logs what it did. The flag is still worth setting: it is the claim made up front, so the
     * Activity never gets the chance to report itself in the window before the first `setScreen`
     * call — which on a slow first frame is the whole difference.
     *
     * Turning this on without calling [LightSession.setScreen] records no screens at all. The SDK
     * says so in the log once, rather than leaving you to notice an empty map — but it cannot fix it
     * for you, because there is nothing to fall back to.
     *
     * The `lightsession-react-native` package sets this; an app wiring the SDK by hand sets it itself.
     */
    val screensReportedByHost: Boolean = false,


    /**
     * Count a tab as its own screen.
     *
     * A screen built from tabs is several screens to the person using it, but one
     * destination to the NavController — so without this the map shows `dashboard` once
     * and every tab's content is invisible. With it on, the selected tab's label is
     * appended (`dashboard › History`) and each tab gets its own node, capture and flow
     * edge, including the edges between the tabs themselves.
     *
     * The label is read from Compose's accessibility semantics, which `Tab` populates
     * through `Modifier.selectable(role = Role.Tab)`. That makes it safe as a name: it is
     * a fixed string in the app's source, not per-user content.
     *
     * Costs one semantics read per touch, measured at ~1.2ms on a mid-range device.
     */
    val trackTabs: Boolean = true,

    /**
     * Count a dialog or a modal bottom sheet as its own screen.
     *
     * Both are real windows in Compose, so opening one is an event the SDK already
     * receives. Dropdown menus and tooltips are windows too and are deliberately excluded
     * — they carry `IsPopup` rather than `IsDialog` — or every combo box in the app would
     * become a screen.
     *
     * **On naming.** A dialog has no route and usually no title the SDK can trust: naming
     * it after its text would turn "Delete Dr. Silva?" into one screen per doctor. So the
     * name comes from `Modifier.testTag` if the app sets one, then from `paneTitle`, and
     * failing both from a hash of the dialog's *shape* — stable across openings, but
     * opaque (`doctors › dialog-1f4a2c`). Adding a testTag to a dialog is what turns that
     * into a readable name.
     *
     * A sheet drawn inside the composition rather than as a window — `BottomSheetScaffold`,
     * a `Box` overlay, `AnimatedVisibility` — is not a window and is not detected.
     */
    val trackModals: Boolean = true,

    /**
     * Look up approximate location from the device's IP address.
     *
     * When on, the SDK calls `/api/v1/ipinfo` once per session and attaches the answer to
     * every batch's device info. What comes back and is stored is: the IP address itself,
     * city, region, country, `loc` — latitude and longitude, city-accurate — the network
     * operator, postal code and timezone.
     *
     * That is personal data under the GDPR and the LGPD, and it is collected without the
     * person being asked, because an IP lookup needs no permission and shows no dialog. An
     * app that ships this is the controller for it: it has to appear in the privacy policy,
     * in the Play Store data-safety form, and in whatever consent flow the app already has.
     * The SDK cannot do any of that on the app's behalf, which is why this is a switch
     * rather than a detail.
     *
     * Turning it off stops the request, not just the field — there is no lookup and nothing
     * to store. The cost is on the dashboard: sessions lose their country, and the map that
     * plots them by coordinate goes empty, since both read out of this.
     *
     * Left on by default so that upgrading the SDK does not silently empty a map somebody is
     * using. For an app shipping to people who are not the developer, off is the posture
     * that needs no justification.
     */
    val collectLocation: Boolean = true,

    /**
     * Begin recording as soon as [LightSession.init] runs.
     *
     * On, which is what every version before this switch did — an SDK that stopped recording
     * because a new flag defaulted off would be a silent outage in every app that took the
     * update.
     *
     * Turn it off for an app that wants one flow rather than the whole run: nothing is collected
     * until [LightSession.startRecording], and nothing again after
     * [LightSession.stopRecording]. Note that each `startRecording` begins a new session, so a
     * flow recorded this way is its own replay rather than a fragment of a longer one — see
     * [LightSession.startRecording] for why.
     *
     * Independent of [enableReplay]. That decides whether frames are ever captured at all; this
     * decides *when* anything is recorded, frames included.
     */
    val startRecordingOnInit: Boolean = true,

    /**
     * Paint the wireframe in the screen's own colours.
     *
     * Off, a wireframe is drawn from a palette keyed on widget type — green for text, purple for
     * buttons — so it reads as a diagram of some app rather than of yours, and a dark-mode screen
     * comes back light.
     *
     * On, each rectangle takes the colour actually inside it, read from a capture the SDK already
     * takes. That is exact by construction: theme, dark mode, photographs, gradients, WebViews and
     * anything drawn by hand all come out right, with nothing to keep in step with Compose.
     *
     * **What it costs in privacy.** A wireframe with no colours cannot leak anything, because
     * there is nothing rendered in it. With colours it carries three numbers per widget, derived
     * from pixels — irreversibly, since no glyph survives being averaged, and the dominant colour
     * of a text block is its background so the text is gone rather than blurred. But "safe by
     * construction" becomes "safe because the rectangles are coarse", which is a weaker claim
     * honestly stated. Sampling also follows [maskText]: a masked capture yields the mask's colour
     * over text, not the paper's.
     *
     * Costs about 18ms per screen mapped — once per screen, not per frame.
     */
    val trueColourWireframes: Boolean = true,

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

    /**
     * Whether errors are captured — crashes by installing an uncaught-exception handler,
     * handled exceptions through [LightSession.captureException].
     *
     * On by default, for the reason crash reporters default on: the crashes most worth having
     * are the ones from the first session after install, which is exactly when nobody has
     * configured anything yet. The handler chains to whatever was installed before it and always
     * forwards the original throwable, so any other crash reporter the app carries — and the
     * system's own crash dialog — behave as if this SDK were not there.
     *
     * Off disables both halves: no handler is installed, and `captureException` becomes a no-op.
     * Errors are not part of replay — [LightSession.stopRecording] stops the pictures, not this.
     *
     * Cost when nothing crashes: zero. The handler sits idle; a handled capture is one JSON
     * build and a queue offer.
     */
    val captureErrors: Boolean = true,
) {
    enum class CaptureQuality {
        LOW, MEDIUM, HIGH
    }

    /**
     * Where the screen-map wireframe gets drawn.
     *
     * Note what is *not* here: real screenshots. Those are [captureRealScreens], which
     * is a separate switch because it is a separate decision — this one picks where a
     * wireframe is drawn, that one decides whether real pixels are stored at all.
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
