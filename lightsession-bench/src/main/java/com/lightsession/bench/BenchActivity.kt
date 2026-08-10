package com.lightsession.bench

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.lightsession.bench.probe.JankProbe
import com.lightsession.bench.probe.LeakProbe
import com.lightsession.bench.probe.Sample
import com.lightsession.bench.probe.Sampler
import com.lightsession.bench.run.ArmResult
import com.lightsession.bench.run.BenchRunner
import com.lightsession.bench.run.InitCost
import com.lightsession.bench.run.LeakHunt
import com.lightsession.bench.run.LeakResult
import com.lightsession.bench.run.RunConfig
import com.lightsession.bench.run.StopCost
import com.lightsession.bench.run.TouchDriver
import com.lightsession.bench.ui.MemoryChart
import com.lightsession.bench.ui.WorkloadList
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The instrument.
 *
 * Everything above the divider measures; everything below it is the thing being measured. That
 * split is deliberate and it is also a constraint on the layout: the workload has to stay on screen
 * for the whole run, because the recorder captures the window, and a workload hidden behind a
 * results table would be a measurement of a results table.
 *
 * Which is why results are only reachable when a run is not in progress.
 */
class BenchActivity : ComponentActivity() {

    private val samples = mutableStateListOf<Sample>()
    private val logLines = mutableStateListOf<String>()

    private var phase by mutableStateOf("idle")
    private var arms by mutableStateOf<List<ArmResult>>(emptyList())
    private var initCost by mutableStateOf<InitCost?>(null)
    private var stopCost by mutableStateOf<StopCost?>(null)
    private var leak by mutableStateOf<LeakResult?>(null)
    private var running by mutableStateOf(false)
    private var showResults by mutableStateOf(false)
    private var armSeconds by mutableStateOf(20L)
    private var maskImages by mutableStateOf(false)
    private var dumping by mutableStateOf(false)

    /**
     * Where the recorder sends.
     *
     * Overridable from the shell because the right value depends on how the device is attached, and
     * getting it wrong silently changes what is measured — see [RunConfig]. On a physical device,
     * `adb reverse tcp:5055 tcp:5055` and `127.0.0.1`.
     */
    private var ingestUrl = RunConfig().ingestUrl
    private var apiUrl = RunConfig().apiUrl
    private var onFirst = false

    private fun runConfig() = RunConfig(
        armSeconds = armSeconds,
        maskImages = maskImages,
        ingestUrl = ingestUrl,
        apiUrl = apiUrl,
        onFirst = onFirst,
    )

    /**
     * Hoisted out of the composition so a run can put it back to a known offset between arms.
     * See `BenchRunner.resetWorkload`.
     */
    private val workloadState = LazyListState()

    private lateinit var sampler: Sampler
    private lateinit var jank: JankProbe
    private lateinit var touch: TouchDriver
    private lateinit var runner: BenchRunner

    private val listener = object : BenchRunner.Listener {
        override fun onPhase(text: String) = runOnUiThread { phase = text }
        override fun onLog(text: String) = runOnUiThread { log(text) }

        override fun onInitCost(cost: InitCost) = runOnUiThread {
            initCost = cost
            log(
                "init: PSS ${kb(cost.pssKb)} · native ${kb(cost.nativeKb)} · " +
                    "code ${kb(cost.codeKb)} · ${signed(cost.threads)} thread(s) · " +
                    "${signed(cost.fds)} fd(s)",
            )
        }

        override fun onArm(result: ArmResult) = runOnUiThread {
            arms = arms + result
            log(
                "${result.label}: peak PSS ${kb(result.peakPssDeltaKb)} · " +
                    "native ${kb(result.peakNativeDeltaKb)} · SDK CPU ${result.sdkCpuMs}ms " +
                    "(${"%.1f".format(result.sdkCpuPercentOfCore)}% of a core) · " +
                    "jank ${"%.1f".format(result.jank.jankPercent)}% of ${result.jank.frames}",
            )
        }

        override fun onStopCost(cost: StopCost) = runOnUiThread {
            stopCost = cost
            log(
                "after stopRecording: retained PSS ${kb(cost.retainedPssKb)} · " +
                    "native ${kb(cost.retainedNativeKb)} · ${cost.threadsRetained} threads alive",
            )
        }

        override fun onDone() = runOnUiThread {
            running = false
            phase = "done"
            logSummary()
            showResults = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sampler = Sampler { sample ->
            samples.add(sample)
            if (samples.size > CHART_WINDOW) samples.removeAt(0)
        }
        sampler.start()
        jank = JankProbe(window)
        touch = TouchDriver(this)
        runner = BenchRunner(application, sampler, jank, touch, ::resetWorkload, listener)

        log("ready — the SDK is not initialised yet, which is the point")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }

        // Driven from a shell, so a run does not depend on someone tapping a button at the right
        // moment — and so the same measurement can be taken from CI:
        //
        //   adb shell am start -n com.lightsession.bench/.BenchActivity --ez autorun true --ei arm 20
        //   adb logcat -s LightSession.Bench
        //
        // Every line the on-screen log shows is also emitted under that tag, so nothing has to be
        // read off a screenshot.
        if (intent?.getBooleanExtra(EXTRA_AUTORUN, false) == true) {
            armSeconds = intent.getIntExtra(EXTRA_ARM_SECONDS, armSeconds.toInt()).toLong()
            maskImages = intent.getBooleanExtra(EXTRA_MASK_IMAGES, false)
            intent.getStringExtra(EXTRA_INGEST)?.let { ingestUrl = it }
            intent.getStringExtra(EXTRA_API)?.let { apiUrl = it }
            onFirst = intent.getBooleanExtra(EXTRA_ON_FIRST, false)
            val leakOnly = intent.getStringExtra(EXTRA_MODE) == MODE_LEAK
            // After the first frame: the workload has to be laid out before the touch driver can
            // aim at it, and `init` should not land in the middle of the first composition.
            window.decorView.postDelayed(
                { if (leakOnly) startLeakHunt() else startRun() },
                AUTORUN_DELAY_MS,
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        touch.stop()
        jank.release()
        sampler.stop()
    }

    /**
     * Middle of the list, not the top: both ends are where a drag overscrolls, and an arm that
     * starts against one of them spends its first seconds rendering the stretch effect.
     */
    private suspend fun resetWorkload() {
        runCatching { workloadState.scrollToItem(WORKLOAD_START_ITEM) }
    }

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize()) {
            Tiles()
            MemoryChart(samples.toList(), Modifier.padding(horizontal = 8.dp))
            Controls()
            Text(
                phase + if (touch.running) "  ·  ${touch.gestures.get()} gestures" else "",
                fontSize = 11.sp,
                color = Color(0xFF9AA0A6),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            HorizontalDivider()
            if (showResults && !running) {
                Results(Modifier.weight(1f))
            } else {
                WorkloadList(state = workloadState, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Tiles() {
        val last = samples.lastOrNull()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tile("PSS", last?.mem?.pssTotalKb?.let { "${it / 1024} MB" } ?: "—")
            Tile("native", last?.mem?.nativeAllocKb?.let { "${it / 1024} MB" } ?: "—")
            Tile("java", last?.mem?.javaUsedKb?.let { "${it / 1024} MB" } ?: "—")
            Tile("threads", last?.mem?.threads?.toString() ?: "—")
            Tile("SDK cpu", "${"%.1f".format(sdkCpuPercentNow())}%")
        }
    }

    @Composable
    private fun Tile(label: String, value: String) {
        Column(
            Modifier
                .background(Color(0xFF1B1F24))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(label, fontSize = 9.sp, color = Color(0xFF9AA0A6))
            Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun Controls() {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = ::startRun, enabled = !running) {
                    Text(if (runner.initialised) "Run A/B" else "Init + run A/B", fontSize = 12.sp)
                }
                OutlinedButton(onClick = ::startLeakHunt, enabled = !running) {
                    Text("Leak hunt", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { showResults = !showResults }, enabled = !running) {
                    Text(if (showResults) "Workload" else "Results", fontSize = 12.sp)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(10L, 20L, 40L).forEach { seconds ->
                    FilterChip(
                        selected = armSeconds == seconds,
                        onClick = { armSeconds = seconds },
                        enabled = !running,
                        label = { Text("${seconds}s", fontSize = 11.sp) },
                    )
                }
                FilterChip(
                    selected = maskImages,
                    onClick = { maskImages = !maskImages },
                    // The SDK reads this once, at init. Changing it afterwards would describe a
                    // configuration the running recorder does not have.
                    enabled = !running && !runner.initialised,
                    label = { Text("mask images", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = dumping,
                    onClick = {
                        dumping = !dumping
                        LeakProbe.dumpingEnabled = dumping
                        log(
                            if (dumping) "heap dumping ON — memory numbers are no longer usable"
                            else "heap dumping off",
                        )
                    },
                    enabled = !running,
                    label = { Text("heap dump", fontSize = 11.sp) },
                )
            }
        }
    }

    @Composable
    private fun Results(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            val off = arms.firstOrNull { !it.recording }
            val on = arms.firstOrNull { it.recording }

            initCost?.let {
                Section("LightSession.init")
                Line("PSS", kb(it.pssKb))
                Line("native", kb(it.nativeKb))
                Line("code (dex/oat)", kb(it.codeKb))
                Line("threads", signed(it.threads))
                Line("file descriptors", signed(it.fds))
            }

            if (off != null && on != null) {
                Section("Per arm — off vs on")
                Compare("peak PSS Δ", kb(off.peakPssDeltaKb), kb(on.peakPssDeltaKb))
                Compare("retained PSS Δ", kb(off.retainedPssKb), kb(on.retainedPssKb))
                Compare("peak native Δ", kb(off.peakNativeDeltaKb), kb(on.peakNativeDeltaKb))
                Compare("retained native Δ", kb(off.retainedNativeKb), kb(on.retainedNativeKb))
                Compare("peak java Δ", kb(off.peakJavaDeltaKb), kb(on.peakJavaDeltaKb))
                Compare("peak graphics Δ", kb(off.peakGraphicsDeltaKb), kb(on.peakGraphicsDeltaKb))
                Compare("threads Δ", "${off.threadsDelta}", "${on.threadsDelta}")
                Compare("fds Δ", "${off.fdsDelta}", "${on.fdsDelta}")

                Section("CPU")
                Compare("SDK threads", "${off.sdkCpuMs} ms", "${on.sdkCpuMs} ms")
                Compare(
                    "SDK, % of a core",
                    "%.1f%%".format(off.sdkCpuPercentOfCore),
                    "%.1f%%".format(on.sdkCpuPercentOfCore),
                )
                Compare(
                    "SDK, % of process",
                    "%.1f%%".format(off.sdkShareOfProcess),
                    "%.1f%%".format(on.sdkShareOfProcess),
                )
                Compare("main thread", "${off.mainCpuMs} ms", "${on.mainCpuMs} ms")
                Compare("whole process", "${off.processCpuMs} ms", "${on.processCpuMs} ms")
                on.perThreadCpuMs.forEach { (name, ms) ->
                    Compare("  $name", "—", "$ms ms")
                }

                Section("Frames — the cost taken from the main thread")
                Compare("frames", "${off.jank.frames}", "${on.jank.frames}")
                Compare(
                    "janky",
                    "%.1f%%".format(off.jank.jankPercent),
                    "%.1f%%".format(on.jank.jankPercent),
                )
                Compare("p50", "%.1f ms".format(off.jank.p50Ms), "%.1f ms".format(on.jank.p50Ms))
                Compare("p95", "%.1f ms".format(off.jank.p95Ms), "%.1f ms".format(on.jank.p95Ms))
                Compare("p99", "%.1f ms".format(off.jank.p99Ms), "%.1f ms".format(on.jank.p99Ms))
                Compare(
                    "worst",
                    "%.1f ms".format(off.jank.worstMs),
                    "%.1f ms".format(on.jank.worstMs),
                )
                Compare("gestures", "${off.gestures}", "${on.gestures}")
                Text(
                    "budget ${"%.2f".format(on.jank.budgetMs)} ms per frame",
                    fontSize = 9.sp,
                    color = Color(0xFF9AA0A6),
                )
            }

            stopCost?.let {
                Section("After stopRecording()")
                Line("retained PSS", kb(it.retainedPssKb))
                Line("retained native", kb(it.retainedNativeKb))
                Line("retained java", kb(it.retainedJavaKb))
                Line("threads still alive", "${it.threadsRetained}")
                Line("watched objects retained", "${it.retainedObjects}")
                Text(
                    "Recorder.shutdown() is only reachable from ReplayIntegration.onTerminate(), " +
                        "which nothing calls. Whatever is above is held for the life of the process.",
                    fontSize = 9.sp,
                    color = Color(0xFF9AA0A6),
                )
            }

            leak?.let {
                Section("Leak hunt")
                Line("watched", "${it.watched}")
                Line("still reachable", "${it.stillReachable}")
                Text(it.note, fontSize = 10.sp, color = Color(0xFF9AA0A6))
            }

            Section("Log")
            logLines.takeLast(40).forEach {
                Text(it, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(
            title,
            fontSize = 12.sp,
            color = Color(0xFF8AB4F8),
            modifier = Modifier.padding(top = 12.dp, bottom = 3.dp),
        )
    }

    @Composable
    private fun Line(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp)
            Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun Compare(label: String, off: String, on: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, modifier = Modifier.weight(1.4f))
            Text(
                off,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF9AA0A6),
                modifier = Modifier.weight(1f),
            )
            Text(on, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        }
    }

    private fun startRun() {
        if (running) return
        running = true
        showResults = false
        arms = emptyList()
        stopCost = null
        lifecycleScope.launch {
            runner.run(runConfig())
        }
    }

    private fun startLeakHunt() {
        if (running) return
        running = true
        phase = "leak hunt"
        lifecycleScope.launch {
            // A recorder that was never started holds nothing, so hunting before init would find
            // nothing and mean nothing.
            runner.ensureInitialised(runConfig())
            com.lightsession.LightSession.getInstance().startRecording()
            val result = LeakHunt(application) { runOnUiThread { phase = it } }.run()
            leak = result
            log("leak hunt: ${result.stillReachable} of ${result.watched} still reachable")
            running = false
            phase = "done"
            showResults = true
        }
    }

    private fun log(line: String) {
        logLines.add(line)
        if (logLines.size > 200) logLines.removeAt(0)
        Log.i(TAG, line)
    }

    /** Percent of one core the SDK's threads used across the last few samples. */
    private fun sdkCpuPercentNow(): Double {
        if (samples.size < 2) return 0.0
        val recent = samples.takeLast(6)
        val first = recent.first().cpu
        val last = recent.last().cpu
        val wall = last.tMs - first.tMs
        if (wall <= 0) return 0.0
        return (last.sdkCpuMs - first.sdkCpuMs) * 100.0 / wall
    }

    private fun kb(value: Int): String =
        if (abs(value) >= 1024) "%+.1f MB".format(value / 1024f) else "%+d KB".format(value)

    /** Deltas always carry their sign, so a drop reads as a drop rather than as `+-1`. */
    private fun signed(value: Int): String = "%+d".format(value)

    /**
     * The comparison, as text, once both arms are in.
     *
     * Printed as well as displayed because the numbers are what the module is for, and reading them
     * off a phone screen is how a measurement stops being reproducible.
     */
    private fun logSummary() {
        val off = arms.firstOrNull { !it.recording } ?: return
        val on = arms.firstOrNull { it.recording } ?: return
        fun row(label: String, a: String, b: String) = log("  %-22s %14s %14s".format(label, a, b))

        // A run where nothing happened still fills the table with tidy-looking zeroes, and it is
        // easy to read it as a result. It happened here: the device screen was off, so the workload
        // never laid out, the touch driver bailed on a zero-height decor view, and the summary
        // reported 0 frames and 0 gestures next to plausible memory numbers.
        if (off.gestures == 0 || on.gestures == 0 || off.jank.frames == 0 || on.jank.frames == 0) {
            log("!! INVALID RUN — gestures ${off.gestures}/${on.gestures}, " +
                "frames ${off.jank.frames}/${on.jank.frames}. The workload did not run; the " +
                "screen was probably off or the app was not in the foreground. Numbers below " +
                "mean nothing.")
        }

        log("─".repeat(54))
        row("", "off", "on")
        row("peak PSS Δ", kb(off.peakPssDeltaKb), kb(on.peakPssDeltaKb))
        row("retained PSS Δ", kb(off.retainedPssKb), kb(on.retainedPssKb))
        row("peak native Δ", kb(off.peakNativeDeltaKb), kb(on.peakNativeDeltaKb))
        row("retained native Δ", kb(off.retainedNativeKb), kb(on.retainedNativeKb))
        row("peak java Δ", kb(off.peakJavaDeltaKb), kb(on.peakJavaDeltaKb))
        row("peak graphics Δ", kb(off.peakGraphicsDeltaKb), kb(on.peakGraphicsDeltaKb))
        row("threads Δ", "${off.threadsDelta}", "${on.threadsDelta}")
        row("SDK cpu", "${off.sdkCpuMs} ms", "${on.sdkCpuMs} ms")
        row(
            "SDK % of a core",
            "%.1f".format(off.sdkCpuPercentOfCore),
            "%.1f".format(on.sdkCpuPercentOfCore),
        )
        row("main thread cpu", "${off.mainCpuMs} ms", "${on.mainCpuMs} ms")
        row("process cpu", "${off.processCpuMs} ms", "${on.processCpuMs} ms")
        row("frames", "${off.jank.frames}", "${on.jank.frames}")
        row("janky", "%.1f%%".format(off.jank.jankPercent), "%.1f%%".format(on.jank.jankPercent))
        row("p50", "%.1f ms".format(off.jank.p50Ms), "%.1f ms".format(on.jank.p50Ms))
        row("p95", "%.1f ms".format(off.jank.p95Ms), "%.1f ms".format(on.jank.p95Ms))
        row("p99", "%.1f ms".format(off.jank.p99Ms), "%.1f ms".format(on.jank.p99Ms))
        row("worst frame", "%.1f ms".format(off.jank.worstMs), "%.1f ms".format(on.jank.worstMs))
        row("gestures", "${off.gestures}", "${on.gestures}")
        row("frames REAIS", "${off.framesUnique}", "${on.framesUnique}")
        row("frames repetidos", "${off.framesRepeated}", "${on.framesRepeated}")
        on.perThreadCpuMs.forEach { (name, ms) -> row("  $name", "—", "$ms ms") }

        // Where the process CPU actually went, which the SDK-only columns above cannot show. On the
        // tablet the named threads explained 30 ms of a 2111 ms increase; everything else was on
        // threads nobody was counting, so this counts all of them.
        val offGroups = off.cpuByThreadGroup
        val onGroups = on.cpuByThreadGroup
        val keys = (offGroups.keys + onGroups.keys)
            .sortedByDescending { (onGroups[it] ?: 0L) - (offGroups[it] ?: 0L) }
        log("─".repeat(54))
        log("cpu por grupo de thread (ordenado pela diferença)")
        row("", "off", "on")
        var shown = 0
        for (key in keys) {
            val a = offGroups[key] ?: 0L
            val b = onGroups[key] ?: 0L
            // Below a couple of clock ticks the difference is not distinguishable from rounding:
            // /proc counts in ticks, which is 10 ms here.
            if (kotlin.math.abs(b - a) < 20 && shown >= 6) continue
            row(key.take(22), "$a ms", "$b ms")
            if (++shown >= THREAD_GROUP_ROWS) break
        }
        row("TOTAL", "${offGroups.values.sum()} ms", "${onGroups.values.sum()} ms")
        log("─".repeat(54))
    }

    private companion object {
        const val TAG = "LightSession.Bench"

        /** 300 samples at 200 ms is the last minute. */
        const val CHART_WINDOW = 300

        /** Thread groups printed in the summary. Enough to cover the total without a wall of rows. */
        const val THREAD_GROUP_ROWS = 16

        /** Where each arm starts. Half of the workload's 600 rows, so neither end is near. */
        const val WORKLOAD_START_ITEM = 300

        const val EXTRA_AUTORUN = "autorun"
        const val EXTRA_ARM_SECONDS = "arm"
        const val EXTRA_MASK_IMAGES = "maskImages"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INGEST = "ingest"
        const val EXTRA_API = "api"
        const val EXTRA_ON_FIRST = "onFirst"
        const val MODE_LEAK = "leak"

        /** Long enough for the first composition and the list's first layout to be done. */
        const val AUTORUN_DELAY_MS = 1_500L
    }
}
