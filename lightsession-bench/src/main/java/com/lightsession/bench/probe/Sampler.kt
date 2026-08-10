package com.lightsession.bench.probe

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/** One tick: memory and CPU read together, so a chart can put them on the same time axis. */
data class Sample(val mem: MemSample, val cpu: CpuSample)

/**
 * Samples the process on a thread of its own and delivers each reading on the main thread.
 *
 * Heap and per-thread CPU are cheap and come out every [FAST_MS]. PSS goes through smaps and is
 * not: it runs every fifth tick, or every second one in [setIntensive], which is turned on while an
 * arm is running so a peak is less likely to fall between readings.
 *
 * Ported from `okhttptest`, with CPU added.
 */
internal class Sampler(private val onSample: (Sample) -> Unit) {

    data class Peaks(
        val pssTotalKb: Int,
        val javaUsedKb: Int,
        val nativeAllocKb: Int,
        val pssCodeKb: Int,
        val pssGraphicsKb: Int,
        val threads: Int,
        val fds: Int,
    ) {
        fun grow(s: MemSample) = Peaks(
            pssTotalKb = maxOf(pssTotalKb, s.pssTotalKb),
            javaUsedKb = maxOf(javaUsedKb, s.javaUsedKb),
            nativeAllocKb = maxOf(nativeAllocKb, s.nativeAllocKb),
            pssCodeKb = maxOf(pssCodeKb, s.pssCodeKb),
            pssGraphicsKb = maxOf(pssGraphicsKb, s.pssGraphicsKb),
            threads = maxOf(threads, s.threads),
            fds = maxOf(fds, s.fds),
        )

        companion object {
            val EMPTY = Peaks(0, 0, 0, 0, 0, 0, 0)
        }
    }

    private val thread = HandlerThread("bench-sampler", Process.THREAD_PRIORITY_BACKGROUND)
    private val main = Handler(Looper.getMainLooper())
    private val memProbe = MemProbe()
    private val cpuProbe = CpuProbe()
    private var handler: Handler? = null
    private var tick = 0
    private var running = false

    @Volatile
    var peaks: Peaks = Peaks.EMPTY
        private set

    @Volatile
    var last: Sample? = null
        private set

    @Volatile
    private var intensive = false

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val every = if (intensive) 2 else 5
            val heavy = tick % every == 0
            tick++
            val sample = Sample(
                mem = memProbe.read(withPss = heavy, withProc = heavy),
                cpu = cpuProbe.read(),
            )
            last = sample
            if (sample.mem.pssFresh) peaks = peaks.grow(sample.mem)
            main.post { onSample(sample) }
            handler?.postDelayed(this, FAST_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (!thread.isAlive) thread.start()
        handler = Handler(thread.looper).also { it.post(loop) }
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    fun setIntensive(on: Boolean) {
        intensive = on
    }

    fun resetPeaks() {
        peaks = Peaks.EMPTY
    }

    companion object {
        const val FAST_MS = 200L
    }
}
