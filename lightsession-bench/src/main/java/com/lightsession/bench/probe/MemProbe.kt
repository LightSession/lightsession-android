package com.lightsession.bench.probe

import android.os.Debug
import android.os.SystemClock
import java.io.File

/**
 * One memory reading of this process, in KB.
 *
 * Ported from the `okhttptest` harness, which measured three HTTP clients the same way. The shape
 * carried over unchanged because none of it was about HTTP: a process either retains memory after a
 * component is told to stop or it does not, and that question is the same whichever component it is.
 *
 * The `pss*` fields come from smaps by way of [Debug.getMemoryInfo], which is expensive enough that
 * it is not read on every tick. When [pssFresh] is false they repeat the last real reading, so the
 * chart series stay continuous without pretending the samples between them were measured.
 */
data class MemSample(
    val tMs: Long,
    /** Java heap in use right now: `totalMemory - freeMemory`. */
    val javaUsedKb: Int,
    /** The process's Java heap ceiling, from `Runtime.maxMemory`. */
    val javaLimitKb: Int,
    /**
     * Native allocations.
     *
     * The one to watch here. A capture's bitmap is a `Bitmap`, and since API 26 a Bitmap's pixels
     * live in native memory rather than on the Java heap — so the recorder's largest single cost
     * moves this number, not [javaUsedKb].
     */
    val nativeAllocKb: Int,
    val pssTotalKb: Int,
    val pssJavaKb: Int,
    val pssNativeKb: Int,
    /** Mapped dex/oat/.so. Where the cost of *loading* the library shows up. */
    val pssCodeKb: Int,
    val pssStackKb: Int,
    /** Textures and surfaces. PixelCopy reads one, so an arm that captures moves this. */
    val pssGraphicsKb: Int,
    val pssOtherKb: Int,
    val threads: Int,
    val fds: Int,
    val pssFresh: Boolean,
)

/**
 * Reads the current process's memory.
 *
 * Not thread-safe — it reuses a single [Debug.MemoryInfo] rather than allocating one per reading,
 * because a probe that allocates on every tick is measuring itself. Use one instance per thread.
 */
internal class MemProbe {

    private val info = Debug.MemoryInfo()

    private var pssTotal = 0
    private var pssJava = 0
    private var pssNative = 0
    private var pssCode = 0
    private var pssStack = 0
    private var pssGraphics = 0
    private var pssOther = 0
    private var threads = 0
    private var fds = 0

    fun read(withPss: Boolean = true, withProc: Boolean = true): MemSample {
        val rt = Runtime.getRuntime()
        val javaUsed = ((rt.totalMemory() - rt.freeMemory()) / 1024L).toInt()
        val javaLimit = (rt.maxMemory() / 1024L).toInt()
        val nativeAlloc = (Debug.getNativeHeapAllocatedSize() / 1024L).toInt()

        if (withPss) {
            Debug.getMemoryInfo(info)
            pssTotal = stat("summary.total-pss", info.totalPss)
            pssJava = stat("summary.java-heap", info.dalvikPrivateDirty)
            pssNative = stat("summary.native-heap", info.nativePrivateDirty)
            pssCode = stat("summary.code", 0)
            pssStack = stat("summary.stack", 0)
            pssGraphics = stat("summary.graphics", 0)
            pssOther = stat("summary.private-other", 0)
        }
        if (withProc) {
            threads = readThreadCount()
            fds = readFdCount()
        }

        return MemSample(
            tMs = SystemClock.elapsedRealtime(),
            javaUsedKb = javaUsed,
            javaLimitKb = javaLimit,
            nativeAllocKb = nativeAlloc,
            pssTotalKb = pssTotal,
            pssJavaKb = pssJava,
            pssNativeKb = pssNative,
            pssCodeKb = pssCode,
            pssStackKb = pssStack,
            pssGraphicsKb = pssGraphics,
            pssOtherKb = pssOther,
            threads = threads,
            fds = fds,
            pssFresh = withPss,
        )
    }

    private fun stat(key: String, fallback: Int): Int =
        info.getMemoryStat(key)?.toIntOrNull() ?: fallback

    /**
     * Collects a few times in a row, to separate memory that is actually retained from garbage that
     * has not been collected yet. Blocks for about 250 ms, so never call it on the main thread.
     *
     * `System.gc()` is a hint and one call proves nothing. Repeating it with
     * [Runtime.runFinalization] in between is the strongest thing a process can ask of ART from
     * inside itself, and it is what makes "retained after stop" mean retained rather than pending.
     */
    fun forceGc() {
        repeat(4) {
            Runtime.getRuntime().gc()
            Runtime.getRuntime().runFinalization()
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun readThreadCount(): Int = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1
        }
    } catch (_: Exception) {
        -1
    }

    private fun readFdCount(): Int = try {
        File("/proc/self/fd").list()?.size ?: -1
    } catch (_: Exception) {
        -1
    }
}
