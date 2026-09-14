package com.lightsession

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.transport.BatchSpool
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a spool write costs the thread that calls it, measured rather than reasoned about.
 *
 * `FlushTriggers.onStop` runs on the main thread and calls `forceFlush(deferFrames = true)`.
 * Frames are deferred to a coroutine; breadcrumbs are deliberately *not* — `processBatch`'s own
 * doc says they are "never deferred", because they are the part that cannot be reconstructed if
 * the process dies. So `spool.writeCrumbs` runs inline, on the main thread, at the moment the app
 * is being backgrounded.
 *
 * The question is what that inline call touches. `writeCrumbs` ends in `enforceBudget`, which
 * calls `sizeBytes` -> `sizeOf`, a recursive `listFiles()`/`length()` walk of the whole spool
 * tree. Reading the code proves the walk exists; it cannot say what a loaded spool turns that
 * into, and a synchronous walk only matters if it is big enough to be felt.
 *
 * ## Why the instrument is a stopwatch and not StrictMode
 *
 * StrictMode was the first attempt and reported zero violations for a write against a seeded
 * spool — which is not the same as "no walk happened". StrictMode's disk detectors hang off the
 * `open`/`read`/`write` path; a directory walk is `getdents` and `stat`, which it does not
 * instrument. A zero there measures StrictMode's coverage, not the SDK.
 *
 * So the measurement is differential: the same call, once against an empty spool and once against
 * a seeded one, on the main thread both times. Everything except the walk is identical between the
 * two — one small file written and renamed — so the difference is the walk and nothing else. The
 * entry count is reported alongside, because that is what the cost scales with.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundFlushDiskTest {

    /** As many pending frame batches as a session that lost connectivity for a few minutes. */
    private val batches = 12

    /** Frames per batch, at the flush-at-frame-count default. */
    private val framesPerBatch = 24

    /** Enough repeats that scheduler noise does not decide the answer. */
    private val samples = 20

    private fun entriesUnder(dir: File): Int =
        if (!dir.exists()) 0
        else (dir.listFiles() ?: emptyArray()).sumOf { 1 + if (it.isDirectory) entriesUnder(it) else 0 }

    private fun timeCrumbWriteOnMainThread(spool: BatchSpool, label: String): Long {
        var total = 0L
        repeat(samples) { index ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val started = System.nanoTime()
                spool.writeCrumbs(
                    batchId = "${label}_${index}_${System.nanoTime()}",
                    fields = mapOf("session_id" to "s", "events" to "[]"),
                )
                total += System.nanoTime() - started
            }
        }
        return total / samples
    }

    @Test
    fun a_crumb_write_on_the_main_thread_walks_the_whole_spool() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "lightsession/spool")

        // A clean tree, so the "empty" baseline really is one.
        root.deleteRecursively()
        val empty = BatchSpool(context.filesDir)
        val emptyCost = timeCrumbWriteOnMainThread(empty, "baseline")
        val emptyEntries = entriesUnder(root)

        // Now seed it. The walk's cost is in the number of entries it stats, not their size, so
        // tiny payloads measure the same syscall count a real 40 MB spool would.
        val loaded = BatchSpool(context.filesDir)
        repeat(batches) { batch ->
            val frames = (0 until framesPerBatch).map { index ->
                BatchSpool.SpooledFrame(
                    fileName = "frame_${index}_${System.currentTimeMillis()}.jpg",
                    bytes = ByteArray(64),
                    isRepeated = false,
                )
            }
            loaded.writeFrames(
                batchId = "seed_${batch}_${System.nanoTime()}",
                metadataJson = "{}",
                frameMetadataJson = frames.map { "{}" },
                frames = frames,
            )
        }
        val loadedEntries = entriesUnder(root)
        val loadedCost = timeCrumbWriteOnMainThread(loaded, "loaded")

        val emptyMicros = emptyCost / 1_000
        val loadedMicros = loadedCost / 1_000

        println("[BackgroundFlushDiskTest] spool entries: empty=$emptyEntries loaded=$loadedEntries")
        println("[BackgroundFlushDiskTest] one crumb write on the MAIN THREAD:")
        println("[BackgroundFlushDiskTest]   empty spool  = ${emptyMicros}us")
        println("[BackgroundFlushDiskTest]   loaded spool = ${loadedMicros}us")
        println("[BackgroundFlushDiskTest]   attributable to the budget walk = ${loadedMicros - emptyMicros}us")

        assertTrue(
            "the seed did not produce a bigger tree, so the comparison is meaningless " +
                "(empty=$emptyEntries loaded=$loadedEntries)",
            loadedEntries > emptyEntries * 2,
        )
        // The regression this pins: a crumb write's cost must not scale with how full the spool
        // is. Before the running counter, the same write against this same seeded tree cost 13ms
        // — 130x the empty baseline — because `enforceBudget` walked every entry on every write.
        // The bound is a generous ratio rather than an absolute number so filesystem noise on a
        // slow emulator cannot fail it, while a reintroduced per-write walk (which scales with
        // the 600-entry seed) cannot pass it.
        assertTrue(
            "a crumb write against a loaded spool cost ${loadedMicros}us vs ${emptyMicros}us " +
                "empty — a per-write cost that scales with spool size is back on this path",
            loadedCost < emptyCost * 20,
        )
    }
}
