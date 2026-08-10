package com.lightsession.bench.probe

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * CPU time, split into what the SDK spent and what the process spent.
 *
 * ## Why this is per-thread and not a delta between two builds
 *
 * The usual way to price a library is to build the app twice and subtract. That works for something
 * called from the caller's own thread — but it charges the difference to the library *and* to every
 * scheduling accident between the two runs, and on a phone those are the same order of magnitude as
 * the thing being measured.
 *
 * The recorder does not need that. Its work runs on threads it names itself —
 * `LightSession-Scheduler`, `LightSession-Encoder` and `ls-pixelcopy` — so the kernel has already
 * done the attribution, and `/proc/self/task/<tid>/stat` reports it. One reading, no second build,
 * no subtraction of two noisy numbers. `LightSessionThreadFactory` names them for exactly this
 * reason: an SDK that cannot be identified in a profile gets blamed for whatever is near it.
 *
 * ## What this deliberately does not count
 *
 * **Coroutine work.** `SessionDataManager` runs on `Dispatchers.IO` and `ScreenMapperIntegration`
 * has a `Dispatchers.Default` scope. Both are process-wide shared pools whose threads are named
 * `DefaultDispatcher-worker-N`, and a host app that uses coroutines shares them — so time on those
 * threads cannot be attributed to anyone. Counting them would overstate the SDK in an app that uses
 * coroutines and understate it in one that does not. [processCpuMs] holds them, unattributed.
 *
 * **Main-thread work, which is the part that matters most.** Mask planning walks the view tree on
 * the main thread and blocks it — measured at 10 to 26 ms per capture while writing
 * `MaskStalenessTest`. That time is charged to the app's own thread, is invisible here, and is felt
 * by the user as jank rather than as CPU. [JankProbe] is what sees it. A harness that reported only
 * [sdkCpuMs] would produce a flattering number and miss the expensive half.
 */
data class CpuSample(
    val tMs: Long,
    /** Total CPU consumed by this process since it started, user + system. */
    val processCpuMs: Long,
    /** Of that, what ran on threads the SDK named. */
    val sdkCpuMs: Long,
    /** The main thread alone. Shared between the app and the SDK's on-thread work. */
    val mainCpuMs: Long,
    /** Per SDK thread, keyed by the (truncated) kernel name. */
    val sdkThreads: Map<String, Long>,
)

/** One thread's CPU, keyed by tid because names repeat and are not unique. */
data class ThreadCpu(val tid: Int, val name: String, val cpuMs: Long)

/**
 * Reads per-thread CPU from `/proc`.
 *
 * Cheap enough to sample at a few hundred milliseconds: it is one `list()` plus one small file read
 * per thread, and only for threads whose name matches.
 */
internal class CpuProbe {

    private companion object {
        /**
         * How the SDK's threads appear to the kernel.
         *
         * The kernel truncates a thread's name to 15 characters — `TASK_COMM_LEN` is 16 including
         * the terminator — so what `/proc` reports is *not* what `Thread(name)` was given:
         *
         * ```
         * LightSession-Scheduler  ->  LightSession-Sc
         * LightSession-Encoder    ->  LightSession-En
         * ls-pixelcopy            ->  ls-pixelcopy      (12 chars, survives whole)
         * ```
         *
         * Matching on the full names finds nothing at all, silently, and the harness reports a
         * library that costs zero CPU. The prefix is 13 characters, so it survives the truncation
         * and covers both executors; the HandlerThread is short enough to match exactly.
         */
        const val SDK_PREFIX = "LightSession-"
        const val PIXEL_COPY_THREAD = "ls-pixelcopy"

        /**
         * `utime` and `stime` in `/proc/[pid]/stat`, as offsets *after* the comm field.
         *
         * They are documented as fields 14 and 15, but the count cannot start at the beginning of
         * the line: field 2 is the thread name in parentheses, and a name may contain both spaces
         * and parentheses, so splitting the whole line on whitespace misaligns everything after it.
         * Parsing resumes at the last `)`, which puts field 3 at index 0 — hence 14-3 and 15-3.
         */
        const val UTIME_AFTER_COMM = 11
        const val STIME_AFTER_COMM = 12

        /** Reads between full rescans of the thread list. At 200 ms a tick, this is every 10 s. */
        const val RESCAN_EVERY = 50
    }

    /** Kernel clock ticks per second; what `stat` counts in. 100 on every Android seen so far. */
    private val ticksPerSecond: Long =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
            .takeIf { it > 0 } ?: 100L

    private val mainTid = Process.myPid().toString()

    /**
     * The tids worth reading, refreshed occasionally rather than every call.
     *
     * Scanning every thread on every tick made the probe the most expensive thing in the process:
     * measured at 4810 ms of CPU across a 20-second arm, against 340 ms for the app's own main
     * thread. An instrument that costs a fifth of the process is perturbing what it measures, and
     * "it cancels out because both arms pay it" only holds for the delta — it still changes
     * scheduling and inflates every absolute number.
     *
     * The SDK's threads are created once at init and live for the process, so the list is stable.
     * Rescanning every [RESCAN_EVERY] reads catches `ls-pixelcopy`, which is created lazily on the
     * first surface capture.
     */
    private var watched: List<String> = emptyList()
    private var readsSinceScan = Int.MAX_VALUE

    fun read(): CpuSample {
        var sdkTotal = 0L
        var mainCpu = 0L
        val perThread = LinkedHashMap<String, Long>()

        if (readsSinceScan >= RESCAN_EVERY) {
            watched = scanForWatchedTids()
            readsSinceScan = 0
        }
        readsSinceScan++

        for (tid in watched) {
            val line = runCatching { File("/proc/self/task/$tid/stat").readText() }.getOrNull()
                ?: continue
            val name = commOf(line) ?: continue
            val isMain = tid == mainTid
            val isSdk = name.startsWith(SDK_PREFIX) || name == PIXEL_COPY_THREAD
            if (!isMain && !isSdk) continue

            val cpuMs = cpuMsOf(line) ?: continue
            if (isMain) mainCpu = cpuMs
            if (isSdk) {
                sdkTotal += cpuMs
                perThread[name] = cpuMs
            }
        }

        return CpuSample(
            tMs = SystemClock.elapsedRealtime(),
            // Whole-process CPU, which the framework already tracks — no need to sum every thread.
            processCpuMs = Process.getElapsedCpuTime(),
            sdkCpuMs = sdkTotal,
            mainCpuMs = mainCpu,
            sdkThreads = perThread,
        )
    }

    /** The full walk, done rarely: main plus anything whose name says it belongs to the SDK. */
    private fun scanForWatchedTids(): List<String> {
        val tids = runCatching { File("/proc/self/task").list() }.getOrNull().orEmpty()
        return tids.filter { tid ->
            if (tid == mainTid) return@filter true
            val line = runCatching { File("/proc/self/task/$tid/stat").readText() }.getOrNull()
                ?: return@filter false
            val name = commOf(line) ?: return@filter false
            name.startsWith(SDK_PREFIX) || name == PIXEL_COPY_THREAD
        }
    }

    /**
     * Every thread in the process, not only the SDK's.
     *
     * [read] answers "what is attributable to the library", which turned out to be the small half of
     * the question. On the tablet, recording cost the process 2111 ms over a 20-second arm while the
     * SDK's own threads accounted for 30 ms of it and the main thread went *down* — leaving about
     * 3.4 seconds on threads nobody was counting. This is what counts them.
     *
     * Keyed by tid rather than name because names are neither unique nor stable: a pool has several
     * `DefaultDispatcher-worker-N`, and the kernel truncates all of them to 15 characters.
     */
    fun readAllThreads(): Map<Int, ThreadCpu> {
        val out = HashMap<Int, ThreadCpu>(64)
        val tids = runCatching { File("/proc/self/task").list() }.getOrNull().orEmpty()
        for (tid in tids) {
            val id = tid.toIntOrNull() ?: continue
            val line = runCatching { File("/proc/self/task/$tid/stat").readText() }.getOrNull()
                ?: continue
            val name = commOf(line) ?: continue
            val cpuMs = cpuMsOf(line) ?: continue
            out[id] = ThreadCpu(id, name, cpuMs)
        }
        return out
    }

    /** The thread name, from between the first `(` and the last `)`. */
    private fun commOf(statLine: String): String? {
        val open = statLine.indexOf('(')
        val close = statLine.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return statLine.substring(open + 1, close)
    }

    private fun cpuMsOf(statLine: String): Long? {
        val close = statLine.lastIndexOf(')')
        if (close < 0) return null
        val fields = statLine.substring(close + 1).trim().split(' ')
        if (fields.size <= STIME_AFTER_COMM) return null
        val utime = fields[UTIME_AFTER_COMM].toLongOrNull() ?: return null
        val stime = fields[STIME_AFTER_COMM].toLongOrNull() ?: return null
        return (utime + stime) * 1000L / ticksPerSecond
    }
}
