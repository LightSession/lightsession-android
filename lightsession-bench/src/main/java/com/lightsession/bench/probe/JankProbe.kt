package com.lightsession.bench.probe

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.FrameMetrics
import android.view.Window

/**
 * How long the app's own frames took, which is where the recorder's real cost shows up.
 *
 * ## Why CPU accounting is not enough
 *
 * [CpuProbe] can attribute the threads the SDK owns, and that number is honest as far as it goes.
 * It just misses the expensive half. Planning a frame's masks walks the view tree **on the main
 * thread** — 10 to 26 ms per capture, measured while writing `MaskStalenessTest` — and that time is
 * charged to the app's thread, not to the library's. It is not CPU the SDK "uses"; it is CPU the SDK
 * *takes*, from the sixteen milliseconds the app had to draw with.
 *
 * A user does not experience that as a percentage. They experience it as a list that stutters. So
 * the measurement has to be frame duration, and the comparison has to be the same scroll with
 * recording off and on.
 *
 * ## Reading the result: p50, not the tail
 *
 * This was written expecting the opposite — that a capture on an interval would leave most frames
 * untouched and show up only in p95 and p99, because the affected frames would *be* the tail. Three
 * runs on a Galaxy Tab A7 said otherwise. **p50 moved every time** (20.7→23.5, 20.3→23.5,
 * 23.2→23.8 ms) while p95 went up, down and up (40.6→41.2, 40.7→37.1, 40.8→41.1).
 *
 * The reason is the capture rate. During a touch the recorder captures every
 * `interactionCaptureIntervalMs` — 100 ms by default, which at 60 Hz is one frame in six. That is
 * not a rare event landing in the tail; it is frequent enough to move the middle of the
 * distribution. The tail belongs to whatever else the device was doing, which is why it is noisy.
 *
 * So read p50 first. A tail that does not move is not evidence that the recorder is free.
 *
 * ## What counts as late
 *
 * From API 31 the platform reports each frame's own [FrameMetrics.DEADLINE], and a frame is late
 * when it misses it. That is used wherever it exists, because the alternative is worse than it
 * looks: comparing against one refresh interval derived from the display's reported rate labels
 * essentially every frame as late. Measured here on an API 36 emulator at 60.000004 Hz, a median
 * frame took 16.8 ms against a 16.67 ms interval — so a threshold of exactly one interval reported
 * 99.7% jank with recording off and 98.7% with it on, which is not a measurement of anything.
 * `TOTAL_DURATION` covers the whole pipeline and routinely lands a fraction over the interval
 * without a frame being dropped.
 *
 * Below API 31 there is no per-frame deadline, so the fallback is two intervals — a frame that has
 * certainly cost a skip rather than one that was merely close.
 */
data class JankStats(
    val frames: Int,
    /** Frames that took longer than the display gave them. */
    val janky: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val worstMs: Double,
    /** One refresh period. What a frame has to fit inside to not be late. */
    val budgetMs: Double,
) {
    val jankPercent: Double get() = if (frames == 0) 0.0 else janky * 100.0 / frames

    companion object {
        val EMPTY = JankStats(0, 0, 0.0, 0.0, 0.0, 0.0, 16.67)
    }
}

/**
 * Collects [FrameMetrics] for a window.
 *
 * The callback is delivered on a thread of this class's own so that measuring frame times does not
 * itself run on the thread whose frame times are being measured.
 */
internal class JankProbe(private val window: Window) {

    private val thread = HandlerThread("bench-jank", Process.THREAD_PRIORITY_BACKGROUND)
    private var handler: Handler? = null
    private var listening = false

    /** Guards the collected state, written from the metrics thread and read from the bench thread. */
    private val lock = Any()
    private val durations = ArrayList<Long>(4096)
    private var lateFrames = 0
    private var deadlineSumNs = 0.0

    /** One refresh period. Only used where the platform reports no per-frame deadline. */
    private val intervalNs: Double = run {
        val hz = window.context.display?.refreshRate?.takeIf { it > 1f } ?: 60f
        1_000_000_000.0 / hz
    }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        // A first-draw frame includes window attach and the first layout of the whole hierarchy.
        // It is real, and it is not what a scroll looks like — leaving it in puts a 200 ms outlier
        // at the top of every percentile and makes the two arms look identical because both are
        // dominated by it.
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0L }?.toDouble()
        } else {
            null
        } ?: (intervalNs * FALLBACK_INTERVALS)

        synchronized(lock) {
            durations.add(total)
            if (total > deadline) lateFrames++
            deadlineSumNs += deadline
        }
    }

    fun start() {
        if (listening) return
        if (!thread.isAlive) thread.start()
        handler = handler ?: Handler(thread.looper)
        window.addOnFrameMetricsAvailableListener(listener, handler)
        listening = true
    }

    fun stop() {
        if (!listening) return
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        listening = false
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    fun reset() {
        synchronized(lock) {
            durations.clear()
            lateFrames = 0
            deadlineSumNs = 0.0
        }
    }

    fun snapshot(): JankStats {
        val (sorted, late, deadlineSum) = synchronized(lock) {
            Triple(durations.toLongArray(), lateFrames, deadlineSumNs)
        }
        if (sorted.isEmpty()) return JankStats.EMPTY.copy(budgetMs = intervalNs / 1_000_000.0)
        sorted.sort()

        return JankStats(
            frames = sorted.size,
            janky = late,
            p50Ms = pct(sorted, 0.50),
            p95Ms = pct(sorted, 0.95),
            p99Ms = pct(sorted, 0.99),
            worstMs = sorted.last() / 1_000_000.0,
            budgetMs = deadlineSum / sorted.size / 1_000_000.0,
        )
    }

    private fun pct(sortedNs: LongArray, p: Double): Double {
        val idx = ((sortedNs.size - 1) * p).toInt()
        return sortedNs[idx] / 1_000_000.0
    }

    private companion object {
        /**
         * Refresh intervals a frame gets before it counts as late, where the platform reports no
         * per-frame deadline. Two, not one: see the class comment — one interval flags almost
         * every frame and measures nothing.
         */
        const val FALLBACK_INTERVALS = 2.0
    }
}
