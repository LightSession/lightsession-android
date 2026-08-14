package com.lightsession.replay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.lightsession.session.Recording
import com.lightsession.mapper.CompositionActivity
import com.lightsession.mapper.ScreenTransition
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.OnTouchEventListener
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.lightsession.LightSession
import com.lightsession.session.SessionDataManager

/**
 * Periodic screen capture.
 *
 * # Why the interval is not a single number
 *
 * Touch data arrives at 60-120Hz. Frames arrive at whatever interval is
 * configured, and in production that has to be around a second — three captures
 * a second is not something you ask of a customer's battery.
 *
 * The consequence is that a swipe happens entirely between two frames. The
 * replay then shows a static screen with a trail drawn over it, then cuts to an
 * already-scrolled screen. The gesture has no visual to be synchronised with,
 * because none was recorded.
 *
 * No amount of work in the renderer fixes that: the frames do not exist. But
 * the information is not spread evenly through a session — measured on a real
 * 385-second session, gestures occupied **0.2% of it**. So a single global
 * interval spends almost its entire budget on a screen nobody is touching, and
 * under-samples the fraction that carries the interaction.
 *
 * Hence two intervals: a slow one while idle, and a burst while something is
 * happening. This is the same trade the repeated-frame signal already makes —
 * spend bytes where the change is — applied to time instead of content.
 *
 * # When the burst ends
 *
 * Not on `ACTION_UP`. A fling keeps animating for several hundred milliseconds
 * after the finger leaves, and that is the part worth seeing. The burst ends
 * when drawing goes quiet, with a hard ceiling so an indefinite animation (a
 * shimmer, a spinner) cannot hold it open forever.
 */
internal class Recorder {

    companion object {
        /** Signal bytes standing in for a frame identical to the previous one. */
        val REPEATED_FRAME_SIGNAL = byteArrayOf(0x52, 0x50, 0x54, 0x44) // "RPTD"

        /** How long a burst survives with no further touch and no drawing. */
        private const val BURST_QUIET_MS = 250L

        /**
         * Ceiling on a single burst, measured from the touch that started it.
         * Without it, a screen that animates forever bursts forever.
         */
        private const val BURST_MAX_MS = 3_000L
    }

    private val screenDrawing = ScreenDrawing()

    /** Set by the draw listener, consumed by the capture tick. */
    private val isScreenContentChanged = AtomicBoolean(false)
    private var isFirstCapture = true

    /** Wall clock until which captures use the burst interval. */
    private val burstUntil = AtomicLong(0)

    /** When the current burst began, for the hard ceiling. */
    private val burstStartedAt = AtomicLong(0)

    private var idleDelayMillis = 1_000L
    private var burstDelayMillis = 100L
    private var scaleFactor = ScreenDrawing.Companion.ScalePresets.MEDIUM_QUALITY
    private var onBitmapBytesReady: ((ByteArray?) -> Unit)? = null

    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor(
            LightSessionThreadFactory("LightSession-Scheduler")
        )
    }

    /**
     * Where JPEG compression happens.
     *
     * Single-threaded on purpose: frames must reach the batch in capture order,
     * and one encoder keeps that guarantee without any sequencing logic. It also
     * bounds how much CPU the SDK can take from the host app.
     */
    private val encoder by lazy {
        Executors.newSingleThreadExecutor(
            LightSessionThreadFactory("LightSession-Encoder")
        )
    }

    private var scheduledFuture: ScheduledFuture<*>? = null

    /**
     * For the half of a capture that has to run on the UI thread.
     *
     * A plain `Handler`, and a `val`. This was a nullable `var` holding a wrapper created inside
     * `capture()`, which made `captureFrame` open with `mainHandler ?: return` — a silent path that
     * dropped a frame for no reason other than the wrapper not existing yet. A handler on the main
     * looper is always constructible, so the branch had nothing to protect against.
     *
     * The wrapper it replaced offered nine methods; one was ever called, and the two other files
     * that need a main-thread handler construct one of these directly.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var contextRef: WeakReference<Context>? = null

    /**
     * One draw listener per window root, not per view.
     *
     * `View.getViewTreeObserver()` returns the *same* observer for every
     * attached view under a window root. Registering a listener per view — which
     * is what this used to do, recursively — therefore put N listeners on one
     * observer, all firing on every draw pass, each one logging a string it had
     * to build first. On a scrolling list that was thousands of calls a second
     * on the UI thread to set a boolean that was already true.
     */
    private val drawListeners = ConcurrentHashMap<Int, DrawRegistration>()

    private val rootViews = mutableSetOf<WeakReference<View>>()

    private data class DrawRegistration(
        val viewRef: WeakReference<View>,
        val listener: ViewTreeObserver.OnDrawListener,
    )

    /**
     * A touch means the user is doing something worth sampling densely.
     *
     * Registered as a Curtains interceptor on each window, so the Recorder does
     * not depend on the interaction-tracking code having been wired up.
     */
    private val touchListener = OnTouchEventListener { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN -> onInteraction()
        }
    }

    /**
     * Starts capturing.
     *
     * @param idleDelayMillis interval while nothing is happening.
     * @param burstDelayMillis interval while a gesture is in progress. Equal to
     *   [idleDelayMillis] disables bursting.
     */
    fun capture(
        context: Context,
        idleDelayMillis: Long,
        burstDelayMillis: Long,
        scaleFactor: Float = ScreenDrawing.Companion.ScalePresets.MEDIUM_QUALITY,
        onBitmapBytesReady: (ByteArray?) -> Unit,
    ) {
        this.idleDelayMillis = idleDelayMillis.coerceAtLeast(50L)
        this.burstDelayMillis = burstDelayMillis.coerceIn(16L, this.idleDelayMillis)
        this.scaleFactor = scaleFactor
        this.onBitmapBytesReady = onBitmapBytesReady

        screenDrawing.setGlobalScaleFactor(scaleFactor)
        contextRef = WeakReference(context)

        isFirstCapture = true
        isScreenContentChanged.set(true)

        // Watches for composition movement, which is how `tick` knows not to capture a frame
        // that straddles two screens. Idempotent, and a no-op in a host without Compose.
        CompositionActivity.start()

        installViewMonitoring()

        Log.d(
            "LightSessionCore",
            "capture started: idle ${this.idleDelayMillis}ms, burst ${this.burstDelayMillis}ms"
        )
        scheduleNext(100L)
    }

    /** True while the burst interval applies. */
    private fun isBursting(now: Long): Boolean {
        val until = burstUntil.get()
        if (until <= now) return false
        val ceiling = burstStartedAt.get() + BURST_MAX_MS
        return now < ceiling
    }

    private fun currentDelay(now: Long): Long =
        if (isBursting(now)) burstDelayMillis else idleDelayMillis

    /** Extends, or opens, the burst window. */
    private fun onInteraction() {
        val now = System.currentTimeMillis()
        if (!isBursting(now)) {
            burstStartedAt.set(now)
        }
        burstUntil.set(now + BURST_QUIET_MS)
    }

    private fun scheduleNext(delayMillis: Long) {
        scheduledFuture = scheduler.schedule(
            { tick() },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun tick() {
        try {
            if (contextRef?.get() == null) {
                Log.w("LightSession", "context gone; capture stopped")
                return
            }

            cleanupDeadViews()

            // Nothing at all while recording is off — not even a repeated-frame marker, which
            // would still be a row on the wire saying the screen was unchanged at this instant.
            // The tick keeps running rather than stopping so `startRecording` needs no restart,
            // and an idle tick is one field read.
            if (!Recording.enabled) {
                isScreenContentChanged.set(false)
                // Reset, so the frame after recording resumes is a real capture: a resumed
                // recording whose first frame were a repeat would hold whatever the renderer had
                // from the previous stretch.
                isFirstCapture = true
                return
            }

            val changed = isScreenContentChanged.getAndSet(false)

            // A screen mid-transition shows two screens at once with both of their masks, and
            // the mask cannot be made correct for such a frame — see [CompositionActivity] — so
            // the frame is not taken.
            //
            // Repeated rather than skipped. Skipping leaves a gap and the renderer holds the
            // frame before it across the gap, which is how an earlier attempt at this made the
            // artefact last longer instead of shorter.
            //
            // Asked of [ScreenTransition] rather than of [CompositionActivity] directly. The
            // latter reports any composition change, which a scrolling list produces on every
            // frame — so this branch used to swallow entire scrolls: measured at 1 real frame
            // against 196 repeats over twenty seconds of dragging.
            val transitioning = ScreenTransition.inProgress()

            if (!isFirstCapture && transitioning) {
                onBitmapBytesReady?.invoke(REPEATED_FRAME_SIGNAL)
                // Left set, so the change is not swallowed: the next quiet tick captures it.
                if (changed) isScreenContentChanged.set(true)
            } else if (isFirstCapture || changed) {
                isFirstCapture = false
                captureFrame()
            } else {
                onBitmapBytesReady?.invoke(REPEATED_FRAME_SIGNAL)
            }
        } catch (e: Throwable) {
            Log.e("LightSession", "capture tick failed", e)
        } finally {
            // Self-rescheduling rather than a fixed period: the interval has to
            // change when a burst starts, and scheduleWithFixedDelay cannot.
            // Measuring from the end of the tick also means a slow capture
            // stretches the interval instead of queueing work behind itself.
            val now = System.currentTimeMillis()
            scheduleNext(currentDelay(now))
        }
    }

    /**
     * Draws on the main thread, encodes off it.
     *
     * The split is the point. `view.draw()` needs the UI thread; JPEG
     * compression does not, and it is the larger half.
     */
    private fun captureFrame() {
        val scale = scaleFactor
        mainHandler.post {
            // Async, because the fallback path is: on a screen holding a hardware bitmap
            // the software draw cannot run at all, and PixelCopy answers on the
            // compositor's schedule. See `ScreenDrawing.captureToBitmapAsync`.
            screenDrawing.captureToBitmapAsync(scale) { bitmap ->
            if (bitmap == null) {
                // A repeat, not nothing.
                //
                // No frame came back — the capture failed, or it was assembled while the screen
                // was drawing and its masks can no longer be trusted over its pixels. Either way
                // there is nothing to send, and the question is what to say instead.
                //
                // Saying nothing leaves a gap, and `CompositionActivity` records what a gap costs:
                // the renderer holds the frame before it across the hole, so the artefact lasts
                // *longer* than the frame that was withheld. A repeat is the signal it already
                // understands, and it is four bytes.
                //
                // Nothing is re-armed here on purpose. The draw that made a frame unusable also
                // sets `isScreenContentChanged`, so the next tick tries again by itself — and once
                // the screen settles, the settled frame is the one that gets captured.
                onBitmapBytesReady?.invoke(REPEATED_FRAME_SIGNAL)
                return@captureToBitmapAsync
            }
            encoder.execute {
                // Delivered from the encoder thread. Everything downstream is
                // thread-safe: SessionDataManager buffers into a
                // ConcurrentLinkedQueue and ReplayIntegration counts atomically.
                val bytes = screenDrawing.encodeToJpeg(bitmap, scale)
                onBitmapBytesReady?.invoke(bytes)
            }
            }
        }
    }

    private fun installViewMonitoring() {
        try {
            cleanupViewMonitoring()
            Curtains.rootViews.forEach { view -> addViewToMonitoring(view, true) }
            Curtains.onRootViewsChangedListeners += onRootViewsChangedListener
            Log.d("LightSessionCore", "monitoring ${drawListeners.size} window root(s)")
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "view monitoring setup failed: $e")
        }
    }

    fun uninstallViewMonitoring() {
        try {
            Curtains.onRootViewsChangedListeners -= onRootViewsChangedListener
            cleanupViewMonitoring()
            isScreenContentChanged.set(false)
            isFirstCapture = true
            burstUntil.set(0)
            screenDrawing.clearObjectPools()
            Log.d("LightSessionCore", "view monitoring uninstalled")
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "view monitoring uninstall failed: $e")
        }
    }

    fun shutdown() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
        // Released with the recorder: a snapshot observer left registered outlives the session
        // and keeps writing a timestamp nothing reads.
        CompositionActivity.stop()
        ScreenTransition.reset()
        uninstallViewMonitoring()
        scheduler.shutdown()
        encoder.shutdown()
        // After the executors, so nothing can still be waiting on a PixelCopy result when
        // its looper goes away.
        screenDrawing.release()
    }

    private val onRootViewsChangedListener = OnRootViewsChangedListener { view, added ->
        addViewToMonitoring(view, added)
    }

    private fun addViewToMonitoring(view: View, added: Boolean = true) {
        try {
            if (!added) {
                removeViewFromMonitoring(view)
                return
            }

            rootViews.add(WeakReference(view))

            val window = view.phoneWindow
            if (window != null && view.windowAttachCount == 0) {
                // Not attached yet; the observer would be a floating one.
                window.onDecorViewReady { setupWindowMonitoring(view) }
            } else {
                setupWindowMonitoring(view)
            }
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "failed to monitor view: $e")
        }
    }

    /** One draw listener and one touch interceptor per window root. */
    private fun setupWindowMonitoring(view: View) {
        try {
            val id = System.identityHashCode(view)
            if (drawListeners.containsKey(id)) return

            val listener = ViewTreeObserver.OnDrawListener {
                // Deliberately bare. This runs on every draw pass of the whole
                // window; the previous version also built and emitted a log line
                // here, per view, per frame.
                isScreenContentChanged.set(true)

                // A burst already open extends while the screen keeps changing —
                // this is what carries a fling past ACTION_UP. It does not open
                // one, or any animation would trigger dense capture on its own.
                val now = System.currentTimeMillis()
                if (isBursting(now)) {
                    burstUntil.set(now + BURST_QUIET_MS)
                }
            }
            view.viewTreeObserver.addOnDrawListener(listener)
            drawListeners[id] = DrawRegistration(WeakReference(view), listener)

            view.phoneWindow?.let { window ->
                window.touchEventInterceptors -= touchListener
                window.touchEventInterceptors += touchListener
            }

            isScreenContentChanged.set(true)
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "failed to set up window monitoring: $e")
        }
    }

    private fun removeViewFromMonitoring(view: View) {
        try {
            val id = System.identityHashCode(view)
            drawListeners.remove(id)?.let { registration ->
                view.viewTreeObserver.removeOnDrawListener(registration.listener)
            }
            view.phoneWindow?.let { it.touchEventInterceptors -= touchListener }
            rootViews.removeAll { it.get() == view || it.get() == null }
        } catch (e: Throwable) {
            Log.e("LightSessionCore", "failed to unmonitor view: $e")
        }
    }

    private fun cleanupViewMonitoring() {
        drawListeners.values.forEach { registration ->
            registration.viewRef.get()?.let { view ->
                try {
                    view.viewTreeObserver.removeOnDrawListener(registration.listener)
                    view.phoneWindow?.let { it.touchEventInterceptors -= touchListener }
                } catch (e: Exception) {
                    Log.w("LightSessionCore", "failed to remove draw listener: $e")
                }
            }
        }
        drawListeners.clear()
        rootViews.clear()
    }

    /** Drops registrations whose view has been collected. */
    private fun cleanupDeadViews() {
        val dead = drawListeners.entries
            .filter { it.value.viewRef.get() == null }
            .map { it.key }
        dead.forEach { drawListeners.remove(it) }
        if (dead.isNotEmpty()) {
            Log.d("LightSessionCore", "cleaned up ${dead.size} dead window root(s)")
        }
    }
}
