package com.lightsession.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What compressing the SDK's real payloads costs on a device, and what it buys.
 *
 * ## Why the payloads are real
 *
 * The assets are bodies captured off the wire from a recording session — a multipart
 * `upload_batch` (two JPEG frames, repeat markers, per-frame metadata), a breadcrumb batch and a
 * skeleton send — because the whole question hinges on *their* entropy. Synthetic JSON compresses
 * however the generator makes it compress; and the surprising number here is real only because the
 * frames are real: a masked, flat UI produces JPEGs whose entropy-coded stream still deflates by a
 * third, which no rule of thumb about "JPEG is incompressible" predicts.
 *
 * Measured on the desktop for ratio (identical anywhere) and here for time, because time is the
 * trade-off being bought: the recorder's encoder thread would pay this per batch.
 *
 * ## Reading the numbers
 *
 * The interesting comparison is per level. If level 6 buys ratio over level 1 for well under a
 * millisecond of extra work, the default is free; if level 9 buys nothing over 6 — it historically
 * does not for payloads this small — there is no reason to ever set it.
 */
@RunWith(AndroidJUnit4::class)
class GzipCostProbeTest {

    private companion object {
        const val TAG = "GzipCostProbe"
        const val WARMUP = 20
        const val RUNS = 100
    }

    private fun deflate(data: ByteArray, level: Int): ByteArray {
        val deflater = Deflater(level, /* gzip-style raw window */ false)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun theCostOfGzipOnRealPayloads() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val payloads = listOf("upload_batch.bin", "breadcrumbs.json", "skeleton.json")
            .map { name -> name to assets.open(name).readBytes() }

        for ((name, data) in payloads) {
            for (level in intArrayOf(1, 6, 9)) {
                repeat(WARMUP) { deflate(data, level) }
                val t0 = System.nanoTime()
                var size = 0
                repeat(RUNS) { size = deflate(data, level).size }
                val perRunMs = (System.nanoTime() - t0) / 1e6 / RUNS
                val throughput = data.size / 1024.0 / 1024.0 / (perRunMs / 1000.0)
                Log.i(
                    TAG,
                    "%-18s L%d: %6d -> %6d B (%4.1f%% menor)  %5.2f ms  %6.1f MB/s".format(
                        name, level, data.size, size,
                        100.0 - size * 100.0 / data.size, perRunMs, throughput,
                    ),
                )
                assertTrue("compressão não reduziu $name", size < data.size)
            }
        }
    }
}
