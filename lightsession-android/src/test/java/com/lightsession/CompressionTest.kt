package com.lightsession

import java.util.zip.GZIPInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The negotiation and the wrapper, piece by piece.
 *
 * A wrong answer here has one of two shapes, and they are not symmetric. Compressing at a server
 * that never advertised is a hard failure — every batch unparseable, spooled, retried and lost.
 * Not compressing at one that did merely leaves the 47% on the table. So the tests lean on the
 * refusing side: every guard that keeps a body plain gets its own case.
 */
class CompressionTest {

    private val json = "application/json".toMediaType()

    @Before
    fun setUp() = Compression.resetForTest()

    @After
    fun tearDown() = Compression.resetForTest()

    private fun response(vararg headers: Pair<String, String>): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("http://ingest.local/upload_batch").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun request(bytes: Int): Request = Request.Builder()
        .url("http://ingest.local/upload_batch")
        .post("x".repeat(bytes).toRequestBody(json))
        .build()

    /** The latch starts down: the first send of every process is plain, whatever the server. */
    @Test
    fun `nothing compresses before the server advertises`() {
        assertFalse(Compression.shouldCompress(request(64_000)))
    }

    @Test
    fun `the advertisement raises the latch and it stays up`() {
        Compression.noteResponse(response(Compression.ADVERTISEMENT to "gzip"))
        assertTrue(Compression.serverAccepts)
        // A later response without the header is a proxy or an error page, not a downgrade.
        Compression.noteResponse(response())
        assertTrue(Compression.serverAccepts)
    }

    @Test
    fun `a response without the header changes nothing`() {
        Compression.noteResponse(response("Content-Type" to "application/json"))
        assertFalse(Compression.serverAccepts)
    }

    @Test
    fun `a small body stays plain`() {
        // A 139-byte flow send grows under gzip's ~23 bytes of framing.
        Compression.noteResponse(response(Compression.ADVERTISEMENT to "gzip"))
        assertFalse(Compression.shouldCompress(request(200)))
        assertTrue(Compression.shouldCompress(request(2_048)))
    }

    @Test
    fun `a body that already carries an encoding is left alone`() {
        Compression.noteResponse(response(Compression.ADVERTISEMENT to "gzip"))
        val encoded = Request.Builder()
            .url("http://ingest.local/upload_batch")
            .header("Content-Encoding", "br")
            .post("x".repeat(4_096).toRequestBody(json))
            .build()
        assertFalse(Compression.shouldCompress(encoded))
    }

    /** The wrapper's whole contract: what the server inflates is what the caller wrote. */
    @Test
    fun `the gzipped body round-trips`() {
        val original = buildString {
            repeat(200) { append("{\"frame\":").append(it).append(",\"kind\":\"jpeg\"}") }
        }
        val body = original.toRequestBody(json)
        val wrapped = Compression.gzipped(body)

        val wire = Buffer().also { wrapped.writeTo(it) }
        assertTrue("compression made it bigger", wire.size < original.length)
        assertEquals(-1L, wrapped.contentLength())
        // Whatever the inner body claims — `toRequestBody` appends a charset — the wrapper
        // repeats verbatim: the server must see the type of the content, not of the wrapping.
        assertEquals(body.contentType(), wrapped.contentType())

        val inflated = GZIPInputStream(wire.inputStream()).readBytes().decodeToString()
        assertEquals(original, inflated)
    }
}
