package com.lightsession.bench.run

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drags the screen the way a finger does, because a programmatic scroll is not the workload.
 *
 * ## Why not `LazyListState.scrollBy`
 *
 * It moves the list, and it moves nothing else. The recorder captures on two schedules: a base
 * interval of `captureIntervalMs` (1 s by default) and a much faster `interactionCaptureIntervalMs`
 * (100 ms) that only runs while the user is touching the screen. A scroll driven from code produces
 * no `MotionEvent`, so it never leaves the slow schedule — the arm would measure a tenth of the
 * capture rate a real scroll causes and report the library as ten times cheaper than it is.
 *
 * It also skips `InteractionAwareCallback` entirely, which is the SDK's only producer of interaction
 * data and sits directly in the touch path of the host app.
 *
 * ## Where the events are injected
 *
 * At [android.view.Window.getCallback], which is the same place the framework delivers them. When
 * the SDK is active that callback *is* its wrapper, so the events traverse exactly what a real
 * touch traverses, including the extra hop the wrapper adds — and that hop is a real cost of
 * integrating, correctly charged to the ON arm. When the SDK is not recording the callback is the
 * Activity, and the same gesture produces the same scroll. The two arms stay comparable.
 *
 * Everything here runs on the main thread; `MotionEvent` dispatch is not valid anywhere else.
 */
internal class TouchDriver(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())

    /** Exposed so the UI can show the gesture actually happening — see [running]. */
    val dispatched = AtomicInteger(0)
    val gestures = AtomicInteger(0)

    @Volatile
    var running = false
        private set

    private var downTime = 0L
    private var step = 0
    private var direction = -1
    private var lastY = 0f

    private val pump = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { emit() }
            // One event per frame. Faster would queue touches the compositor never catches up
            // with, which turns a scroll measurement into a MotionEvent-allocation measurement.
            handler.postDelayed(this, FRAME_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        step = 0
        handler.post(pump)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(pump)
        // Leaving a gesture open would keep the SDK on its fast capture schedule after the arm
        // ended, so the next arm's baseline would be taken while it was still bursting.
        if (step in 1 until MOVES + 1) {
            runCatching { send(MotionEvent.ACTION_CANCEL, lastY) }
        }
        step = 0
    }

    private fun emit() {
        val decor = activity.window?.decorView ?: return
        val height = decor.height.takeIf { it > 0 } ?: return
        val top = height * BAND_TOP
        val bottom = height * BAND_BOTTOM
        val travel = bottom - top

        when (step) {
            0 -> {
                downTime = SystemClock.uptimeMillis()
                // Starts at one end of the band and drags to the other, alternating. Alternating
                // keeps the list off both of its ends, where a drag scrolls nothing and the arm
                // would quietly become the still-screen case.
                lastY = if (direction < 0) bottom else top
                send(MotionEvent.ACTION_DOWN, lastY)
            }

            in 1..MOVES -> {
                lastY += direction * (travel / MOVES)
                send(MotionEvent.ACTION_MOVE, lastY)
            }

            else -> {
                send(MotionEvent.ACTION_UP, lastY)
                gestures.incrementAndGet()
                direction = -direction
                step = -1 // becomes 0 below
            }
        }
        step++
    }

    private fun send(action: Int, y: Float) {
        val decor = activity.window?.decorView ?: return
        val x = decor.width / 2f
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try {
            // Not `activity.dispatchTouchEvent`: that is what the callback calls *into*, so going
            // straight there would bypass the wrapper this is meant to exercise.
            activity.window?.callback?.dispatchTouchEvent(event)
            dispatched.incrementAndGet()
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val FRAME_MS = 16L

        /** Moves per gesture. Twenty at ~60 Hz is a third of a second of finger travel. */
        const val MOVES = 20

        /**
         * The band of the screen the gesture stays inside, as a fraction of window height.
         *
         * The workload list occupies the lower half; the controls and the chart are above it. A
         * drag that strayed over the controls would press them, and a run that reconfigures itself
         * halfway through is not a measurement.
         */
        const val BAND_TOP = 0.56f
        const val BAND_BOTTOM = 0.95f
    }
}
