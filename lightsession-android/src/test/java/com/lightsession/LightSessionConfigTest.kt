package com.lightsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config's `require` block is the only place several of these invariants are
 * stated, and a couple of them are the kind that would produce plausible-looking
 * but wrong behaviour rather than a crash.
 */
class LightSessionConfigTest {

    private fun config(
        captureIntervalMs: Long = 1_000,
        interactionCaptureIntervalMs: Long = 100,
        sessionTimeoutMs: Long = 30_000,
        flushAtFrameCount: Int = 24,
        flushAtBytes: Long = 2L * 1024 * 1024,
        maxBufferedBytes: Long = 8L * 1024 * 1024
    ) = LightSessionConfig(
        apiKey = "dev-key",
        ingestUrl = "http://localhost:5055",
        apiUrl = "http://localhost:3002",
        captureIntervalMs = captureIntervalMs,
        interactionCaptureIntervalMs = interactionCaptureIntervalMs,
        sessionTimeoutMs = sessionTimeoutMs,
        flushAtFrameCount = flushAtFrameCount,
        flushAtBytes = flushAtBytes,
        maxBufferedBytes = maxBufferedBytes
    )

    @Test
    fun `the defaults are usable`() {
        val c = config()
        assertEquals(30_000, c.sessionTimeoutMs)
        assertTrue(c.maxBufferedBytes > c.flushAtBytes)
    }

    @Test
    fun `the memory ceiling must sit above the flush threshold`() {
        // Otherwise every flush trigger already exceeds the ceiling, so `addFrame`
        // takes the shed branch instead of the flush branch and the SDK discards
        // frames continuously while looking like it is working.
        val error = assertThrows(IllegalArgumentException::class.java) {
            config(flushAtBytes = 4L * 1024 * 1024, maxBufferedBytes = 2L * 1024 * 1024)
        }
        assertTrue(error.message!!.contains("must exceed"))
    }

    @Test
    fun `an equal ceiling and threshold is also refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(flushAtBytes = 1024 * 1024, maxBufferedBytes = 1024 * 1024)
        }
    }

    @Test
    fun `a session timeout shorter than five seconds is refused`() {
        // It has to match the ingest service's idle timeout. Anything this short is
        // a typo, and it would split one visit into a stream of one-batch sessions.
        assertThrows(IllegalArgumentException::class.java) { config(sessionTimeoutMs = 1_000) }
    }

    @Test
    fun `a non positive flush count is refused`() {
        assertThrows(IllegalArgumentException::class.java) { config(flushAtFrameCount = 0) }
    }

    @Test
    fun `a tiny byte threshold is refused`() {
        // A threshold below one frame would flush on every capture, turning the
        // batching into one HTTP request per frame.
        assertThrows(IllegalArgumentException::class.java) { config(flushAtBytes = 1_024) }
    }

    @Test
    fun `the burst interval cannot exceed the idle interval`() {
        // Bursting slower than idle is not a burst. Pre-existing invariant, kept
        // here because the new thresholds sit next to it.
        assertThrows(IllegalArgumentException::class.java) {
            config(captureIntervalMs = 200, interactionCaptureIntervalMs = 500)
        }
    }

    @Test
    fun `urls are required and get their trailing slash trimmed`() {
        assertThrows(IllegalArgumentException::class.java) {
            LightSessionConfig(apiKey = "k", ingestUrl = "", apiUrl = "http://x")
        }
        val c = LightSessionConfig(
            apiKey = "k",
            ingestUrl = "http://localhost:5055/",
            apiUrl = "http://localhost:3002/"
        )
        assertEquals("http://localhost:5055", c.normalizedIngestUrl)
        assertEquals("http://localhost:3002", c.normalizedApiUrl)
    }
}
