package com.lightsession.bench.run

import android.app.Application
import android.os.SystemClock
import android.os.Trace
import com.lightsession.LightSession
import com.lightsession.LightSessionConfig
import com.lightsession.bench.probe.CpuProbe
import com.lightsession.bench.probe.CpuSample
import com.lightsession.bench.probe.JankProbe
import com.lightsession.bench.probe.JankStats
import com.lightsession.bench.probe.LeakProbe
import com.lightsession.bench.probe.MemProbe
import com.lightsession.bench.probe.MemSample
import com.lightsession.bench.probe.Sampler
import com.lightsession.bench.probe.ThreadCpu
import com.lightsession.replay.ReplayStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What one arm costs, always as a difference against the state immediately before it.
 *
 * The absolute numbers are meaningless on their own — they depend on the device, on what the app
 * did before, and on how recently ART collected. Only the deltas transfer.
 */
internal data class ArmResult(
    val label: String,
    val recording: Boolean,
    val durationMs: Long,
    val baseline: MemSample,
    val peaks: Sampler.Peaks,
    val afterRun: MemSample,
    /** After a real collection. What is left here is retained, not merely uncollected. */
    val afterGc: MemSample,
    val cpuStart: CpuSample,
    val cpuEnd: CpuSample,
    /** Every thread in the process, at the two ends of the arm. See [cpuByThreadGroup]. */
    val threadsStart: Map<Int, ThreadCpu>,
    val threadsEnd: Map<Int, ThreadCpu>,
    val jank: JankStats,
    val gestures: Int,
    /** Real frames and repeat markers the recorder produced during the arm. See `ReplayStats`. */
    val framesUnique: Long,
    val framesRepeated: Long,
) {
    val peakPssDeltaKb get() = peaks.pssTotalKb - baseline.pssTotalKb
    val retainedPssKb get() = afterGc.pssTotalKb - baseline.pssTotalKb
    val peakJavaDeltaKb get() = peaks.javaUsedKb - baseline.javaUsedKb
    val retainedJavaKb get() = afterGc.javaUsedKb - baseline.javaUsedKb

    /** The one to watch: a capture's bitmap is native memory on API 26 and up. */
    val peakNativeDeltaKb get() = peaks.nativeAllocKb - baseline.nativeAllocKb
    val retainedNativeKb get() = afterGc.nativeAllocKb - baseline.nativeAllocKb
    val peakGraphicsDeltaKb get() = peaks.pssGraphicsKb - baseline.pssGraphicsKb
    val threadsDelta get() = peaks.threads - baseline.threads
    val fdsDelta get() = peaks.fds - baseline.fds

    val sdkCpuMs get() = cpuEnd.sdkCpuMs - cpuStart.sdkCpuMs
    val processCpuMs get() = cpuEnd.processCpuMs - cpuStart.processCpuMs
    val mainCpuMs get() = cpuEnd.mainCpuMs - cpuStart.mainCpuMs

    /** Percent of one core the SDK's own threads held for the length of the arm. */
    val sdkCpuPercentOfCore get() = if (durationMs <= 0) 0.0 else sdkCpuMs * 100.0 / durationMs

    /** How much of everything this process burned was on threads the SDK named. */
    val sdkShareOfProcess get() = if (processCpuMs <= 0) 0.0 else sdkCpuMs * 100.0 / processCpuMs

    val perThreadCpuMs: Map<String, Long>
        get() = cpuEnd.sdkThreads.mapValues { (name, end) ->
            end - (cpuStart.sdkThreads[name] ?: 0L)
        }

    /**
     * CPU spent during the arm, grouped by kind of thread.
     *
     * Names are normalised by replacing digits with `#`, so the eight members of a dispatcher pool
     * or the dozen `Binder:1234_5` threads collapse into one row. Without that, the interesting
     * total is spread across a dozen lines that each look like noise.
     *
     * A tid that only appears at the end started inside the arm, so all of its time is arm work. A
     * tid whose name changed was recycled for a different thread, and is treated the same way — the
     * alternative is subtracting one thread's total from another's.
     *
     * Threads that ended before the arm did are lost. There is no way to see them from here, and it
     * biases every number in this map slightly low.
     */
    val cpuByThreadGroup: Map<String, Long>
        get() {
            val out = HashMap<String, Long>()
            for ((tid, end) in threadsEnd) {
                val start = threadsStart[tid]
                val delta =
                    if (start != null && start.name == end.name) end.cpuMs - start.cpuMs
                    else end.cpuMs
                if (delta > 0) out.merge(groupOf(end.name), delta, Long::plus)
            }
            return out
        }

    private fun groupOf(name: String): String = name.replace(Regex("[0-9]+"), "#")
}

/** What calling `init` costs at startup: threads, loaded classes, whatever it allocates. */
internal data class InitCost(val before: MemSample, val after: MemSample) {
    val pssKb get() = after.pssTotalKb - before.pssTotalKb
    val javaKb get() = after.javaUsedKb - before.javaUsedKb
    val nativeKb get() = after.nativeAllocKb - before.nativeAllocKb
    val codeKb get() = after.pssCodeKb - before.pssCodeKb
    val threads get() = after.threads - before.threads
    val fds get() = after.fds - before.fds
}

/**
 * What survives `stopRecording()`.
 *
 * This is the question reading the code raised and could not answer. `stopRecording()` lowers a flag
 * and flushes; the teardown that clears the bitmap pool and quits the `ls-pixelcopy` thread lives in
 * `Recorder.shutdown()`, whose only caller is `ReplayIntegration.onTerminate()` — which nothing
 * calls, and which `Application.onTerminate` would not deliver on a real device anyway.
 *
 * So an app that turns recording off keeps whatever the recorder was holding for the rest of the
 * process's life. Whether that is a rounding error or two full-screen bitmaps and three threads is
 * not a code-reading question. It is [retainedPssKb] and [threadsRetained].
 */
internal data class StopCost(
    val beforeStop: MemSample,
    val afterStopGc: MemSample,
    /** Watched objects still reachable after the collection. See [LeakProbe]. */
    val retainedObjects: Int,
) {
    val retainedPssKb get() = afterStopGc.pssTotalKb - beforeStop.pssTotalKb
    val retainedNativeKb get() = afterStopGc.nativeAllocKb - beforeStop.nativeAllocKb
    val retainedJavaKb get() = afterStopGc.javaUsedKb - beforeStop.javaUsedKb
    val threadsRetained get() = afterStopGc.threads
    val fdsRetained get() = afterStopGc.fds
}

/**
 * How the run is shaped.
 *
 * [ingestUrl] decides which of two real situations is measured, and they are different. Pointed at
 * a listening ingest, the ON arm includes serialising and sending. Pointed at nothing, sends fail,
 * the batch spools to disk and retries, which is the offline user. Both are honest; they are not
 * the same number, so the log records which one ran.
 *
 * What is *not* honest is the third case, and it was the default here until a physical device
 * showed it up: `10.0.2.2` is the emulator's alias for the host machine and means nothing on a real
 * phone. Pointed there, every send waits out a connect timeout to an unroutable address, and the
 * arm measures the network stack giving up. Use `adb reverse tcp:5055 tcp:5055` and point at
 * `127.0.0.1` — see the README.
 */
internal data class RunConfig(
    val armSeconds: Long = 20,
    /**
     * Seconds of workload thrown away before anything is measured, twice: once before `init` to
     * warm the app, once with the recorder running to warm the SDK's own paths.
     *
     * Not optional in practice. Without it, on a Galaxy Tab A7 (API 31, Snapdragon 662), the first
     * arm paid for every class load, JIT pass and first composition in the app, and the comparison
     * came out backwards — recording off measured 32.2% jank and p95 46.7 ms against 15.0% and
     * 37.4 ms with recording on, which reads as the library making the app faster. It does not. It
     * means the first arm was measuring startup.
     *
     * The emulator hid this: fast enough that warm-up finished inside the first arm's settle.
     */
    val warmupSeconds: Long = 12,
    /**
     * Runs the ON arm first.
     *
     * A diagnostic, not a setting. Whatever runs first carries any warm-up the discarded passes did
     * not absorb, so a difference that is really about position looks exactly like a difference
     * about recording. Running the sequence both ways separates them: a penalty that stays with
     * `recording on` is the library, one that stays with the first arm is not.
     */
    val onFirst: Boolean = false,
    val settleMs: Long = 1_200,
    val maskText: Boolean = true,
    /** Off is the SDK's default. On makes the mask planner walk and paint image nodes too. */
    val maskImages: Boolean = false,
    val ingestUrl: String = "http://10.0.2.2:5055",
    val apiUrl: String = "http://10.0.2.2:3002",
)

/**
 * Runs the sequence, in the order the `okhttptest` harness established.
 *
 * ```
 * GC -> baseline -> [init once] -> arm OFF -> arm ON -> stopRecording -> GC -> retained
 * ```
 *
 * Both arms drag the same list with the same synthetic gestures for the same number of seconds. The
 * only difference between them is whether the recorder is running, which is what makes the
 * difference attributable to it.
 *
 * The OFF arm runs first on purpose: it pays for whatever the app itself still has to warm up —
 * class loading, the first compositions, the list's initial layout — so the ON arm is not charged
 * for it. That does bias the comparison slightly in the SDK's favour, which is the direction an
 * honest harness should lean. Running the sequence twice and reading the second is better still.
 */
internal class BenchRunner(
    private val app: Application,
    private val sampler: Sampler,
    private val jank: JankProbe,
    private val touch: TouchDriver,
    /**
     * Puts the workload back where it started, before each arm's baseline.
     *
     * Without it the two arms did not begin at the same scroll offset, and the trace showed what
     * that costs: 572 `drawLayer [AndroidEdgeEffectOverscrollEffect]` slices in the ON arm and none
     * in the OFF one. Android 12's stretch overscroll renders through a `RenderEffect`, which needs
     * an offscreen layer — about 550 ms of RenderThread work that appeared as a cost of recording
     * and was really a cost of the list having reached its end in one arm and not the other.
     */
    private val resetWorkload: suspend () -> Unit,
    private val listener: Listener,
) {

    interface Listener {
        fun onPhase(text: String)
        fun onLog(text: String)
        fun onInitCost(cost: InitCost)
        fun onArm(result: ArmResult)
        fun onStopCost(cost: StopCost)
        fun onDone()
    }

    private val probe = MemProbe()
    private val cpu = CpuProbe()

    @Volatile
    var initialised = false
        private set

    @Volatile
    var busy = false
        private set

    suspend fun run(cfg: RunConfig) = withContext(Dispatchers.Default) {
        if (busy) return@withContext
        busy = true
        try {
            listener.onLog(
                "arm ${cfg.armSeconds}s · warmup ${cfg.warmupSeconds}s · " +
                    "order ${if (cfg.onFirst) "on→off" else "off→on"} · " +
                    "maskImages=${cfg.maskImages} · ingest ${cfg.ingestUrl}",
            )

            phase("collecting, settling")
            probe.forceGc()
            delay(cfg.settleMs)

            if (!initialised) {
                // Before `init`, so the baseline it is measured against is a settled app rather
                // than one still loading itself. On the tablet an unwarmed baseline reported init
                // as *freeing* half a megabyte.
                warmUp("warming the app", cfg)
            }
            ensureInitialised(cfg)

            // And again with the recorder running, so the SDK's own classes are loaded and its
            // paths JIT-compiled before either arm is timed. Otherwise the ON arm pays for that
            // and the OFF arm does not, which is the same bias in the other direction.
            phase("warming the recorder")
            withContext(Dispatchers.Main) { LightSession.getInstance().startRecording() }
            warmUp("warming the recorder", cfg)
            withContext(Dispatchers.Main) { LightSession.getInstance().stopRecording() }
            // Longer than the usual settle, and the reason is specific: `stopRecording` forces a
            // flush of everything the warm-up recorded, and that flush is the SDK serialising and
            // sending a full batch. At one settle it was still running when the OFF arm took its
            // baseline, so the arm with recording *off* was measuring the recorder — which is how
            // it came out consistently worse than the ON arm on the tablet.
            phase("draining the warm-up's flush")
            delay(cfg.settleMs * 4)

            val order = if (cfg.onFirst) listOf(true, false) else listOf(false, true)
            for (recording in order) {
                val label = if (recording) "recording on" else "recording off"
                listener.onArm(runArm(label, recording, cfg))
            }

            phase("stopRecording, then collecting")
            val beforeStop = probe.read()
            withContext(Dispatchers.Main) { LightSession.getInstance().stopRecording() }
            // Twice the usual settle: stopRecording forces a flush, and the send has to finish (or
            // fail) before what is left can be called retained rather than in flight.
            delay(cfg.settleMs * 2)
            probe.forceGc()
            delay(300)
            val afterStop = probe.read()
            listener.onStopCost(
                StopCost(beforeStop, afterStop, LeakProbe.retainedCount),
            )

            phase("idle")
            listener.onDone()
        } finally {
            sampler.setIntensive(false)
            withContext(Dispatchers.Main) { touch.stop(); jank.stop() }
            busy = false
        }
    }

    /**
     * Starts the SDK, once, reporting what that cost.
     *
     * Also reachable from the leak hunt, which needs a running recorder to have anything to hold on
     * to — hunting for a leak in a library that was never started would find nothing and prove
     * nothing.
     */
    suspend fun ensureInitialised(cfg: RunConfig) {
        if (initialised) return
        // Collected here rather than only in `run`, so the baseline means the same thing whichever
        // entry point got here. Without it the leak hunt read a baseline that still held the app's
        // startup garbage and reported init as *freeing* 1.7 MB — the collection that happened to
        // fall inside the measured window, credited to the library.
        phase("collecting before the baseline")
        probe.forceGc()
        delay(cfg.settleMs)

        val before = probe.read()
        phase("LightSession.init")
        withContext(Dispatchers.Main) {
            LightSession.getInstance().init(app, configFor(cfg))
        }
        delay(cfg.settleMs)
        probe.forceGc()
        val after = probe.read()
        initialised = true
        listener.onInitCost(InitCost(before, after))
    }

    /** Runs the workload and measures nothing. See [RunConfig.warmupSeconds]. */
    private suspend fun warmUp(label: String, cfg: RunConfig) {
        if (cfg.warmupSeconds <= 0) return
        phase("$label for ${cfg.warmupSeconds}s (discarded)")
        withContext(Dispatchers.Main) { touch.start() }
        delay(cfg.warmupSeconds * 1_000)
        withContext(Dispatchers.Main) { touch.stop() }
        delay(cfg.settleMs)
    }

    private suspend fun runArm(label: String, recording: Boolean, cfg: RunConfig): ArmResult {
        phase("$label — preparing")
        withContext(Dispatchers.Main) { resetWorkload() }
        withContext(Dispatchers.Main) {
            if (recording) LightSession.getInstance().startRecording()
            else LightSession.getInstance().stopRecording()
        }
        // startRecording rolls a new session and stopRecording forces a flush. Both are real work
        // caused by the toggle rather than by the arm, so they happen before the baseline is taken.
        delay(cfg.settleMs)
        probe.forceGc()
        delay(200)

        val baseline = probe.read()
        val cpuStart = cpu.read()
        val threadsStart = cpu.readAllThreads()
        val uniqueStart = ReplayStats.unique
        val repeatedStart = ReplayStats.repeated
        val gesturesStart = touch.gestures.get()
        sampler.resetPeaks()
        sampler.setIntensive(true)
        jank.reset()

        phase("$label — dragging for ${cfg.armSeconds}s")
        // Marks the measured window inside a system trace, so a Perfetto/atrace capture can be cut
        // to exactly this arm. Correlating by wall clock does not work: ftrace timestamps are
        // CLOCK_BOOTTIME and logcat's are CLOCK_MONOTONIC, and on a device that has suspended a lot
        // those differ by days. An async section is used rather than begin/end because this
        // function suspends and resumes on different threads, which the synchronous form forbids.
        val traceCookie = if (recording) TRACE_COOKIE_ON else TRACE_COOKIE_OFF
        Trace.beginAsyncSection(TRACE_ARM, traceCookie)
        withContext(Dispatchers.Main) {
            jank.start()
            touch.start()
        }
        val t0 = SystemClock.elapsedRealtime()
        delay(cfg.armSeconds * 1_000)
        val durationMs = SystemClock.elapsedRealtime() - t0
        withContext(Dispatchers.Main) {
            touch.stop()
            jank.stop()
        }
        Trace.endAsyncSection(TRACE_ARM, traceCookie)

        val cpuEnd = cpu.read()
        val threadsEnd = cpu.readAllThreads()
        val jankStats = jank.snapshot()
        val afterRun = probe.read()
        // The sampler may have missed the exact peak between ticks; folding in the two readings
        // taken around the arm means the reported peak is never below something actually observed.
        val peaks = sampler.peaks.grow(afterRun).grow(baseline)
        sampler.setIntensive(false)

        phase("$label — settling, then collecting")
        delay(cfg.settleMs)
        probe.forceGc()
        delay(200)
        val afterGc = probe.read()

        return ArmResult(
            label = label,
            recording = recording,
            durationMs = durationMs,
            baseline = baseline,
            peaks = peaks,
            afterRun = afterRun,
            afterGc = afterGc,
            cpuStart = cpuStart,
            cpuEnd = cpuEnd,
            threadsStart = threadsStart,
            threadsEnd = threadsEnd,
            jank = jankStats,
            gestures = touch.gestures.get() - gesturesStart,
            framesUnique = ReplayStats.unique - uniqueStart,
            framesRepeated = ReplayStats.repeated - repeatedStart,
        )
    }

    private fun configFor(cfg: RunConfig) = LightSessionConfig(
        apiKey = "bench",
        ingestUrl = cfg.ingestUrl,
        apiUrl = cfg.apiUrl,
        maskText = cfg.maskText,
        maskImages = cfg.maskImages,
        // The whole point of this module. Every other app in the repo starts recording on init,
        // which would mean the OFF arm never existed.
        startRecordingOnInit = false,
    )

    private fun phase(text: String) = listener.onPhase(text)

    private companion object {
        /** The slice name a trace capture is cut on. Cookie tells the two arms apart. */
        const val TRACE_ARM = "ls-bench-arm"
        const val TRACE_COOKIE_OFF = 1
        const val TRACE_COOKIE_ON = 2
    }
}
