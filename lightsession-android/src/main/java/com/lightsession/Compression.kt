package com.lightsession

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.GzipSink
import okio.buffer

/**
 * Compresses request bodies, once the server says it can take them.
 *
 * ## What this buys, measured
 *
 * Everything the SDK sends is text or masked flat-UI JPEG, and both deflate. Captured off a real
 * recording session and compressed at gzip's default level: a frame batch shrinks 37% — the JPEGs
 * inside are pictures of a masked, flat UI, whose entropy-coded stream still repeats — a
 * breadcrumb batch 87%, a skeleton send 63%. Over the whole session that is 2.29 MB down to
 * 1.22 MB: 47% of the app's upstream bandwidth, which on a phone is also radio time and therefore
 * battery.
 *
 * The price was measured on device in `GzipCostProbeTest` with the same real payloads: 0.32 ms for
 * the 26 KB batch, 0.11 ms for the breadcrumbs — on the encoder or IO thread that was already
 * paying milliseconds for JPEG and disk. Level 6 (the default) is the whole trade: level 9 buys
 * 0.0–0.8% for up to half again the time, level 1 saves microseconds and loses a percent.
 *
 * ## Why the SDK asks first
 *
 * A gzipped body at a server that does not decompress is not degradation, it is a hard parse
 * failure — every batch 400s, spools, retries and dies. So nothing is compressed until the server
 * *advertises*: every response from an ingest that can decompress carries
 * `X-LS-Accept-Encoding: gzip`, the first (always plain) send of the process reads it, and the
 * latch flips for good. An old server never says it and never receives gzip; a new SDK against it
 * costs exactly one uncompressed session, forever correct. No deploy order, no configuration flag
 * to forget.
 *
 * ## What is left alone
 *
 * Bodies under [MIN_BYTES]: a 139-byte flow send grows under gzip's header. Bodies already
 * carrying a `Content-Encoding`: they know something this class does not.
 */
internal object Compression {

    /** `X-LS-Accept-Encoding`, sent by servers whose request path decompresses. */
    const val ADVERTISEMENT = "X-LS-Accept-Encoding"

    /**
     * Below this, the gzip wrapper costs more than it saves: header plus trailer is ~23 bytes and
     * small JSON barely deflates past its own noise.
     */
    const val MIN_BYTES = 1_024L

    /**
     * Whether any response this process has seen advertised gzip.
     *
     * Never unset. A server that advertised and then stopped decompressing is a rollback across an
     * incompatible boundary, and the SDK cannot tell it apart from a server that is briefly
     * unreachable — the deploy that removes the layer is the deploy that owns that outage.
     */
    @Volatile
    var serverAccepts: Boolean = false
        private set

    /** For tests. Production never lowers the latch. */
    internal fun resetForTest() {
        serverAccepts = false
    }

    internal fun noteResponse(response: Response) {
        if (!serverAccepts &&
            response.header(ADVERTISEMENT)?.contains("gzip", ignoreCase = true) == true
        ) {
            serverAccepts = true
        }
    }

    /** Whether this request is worth compressing, given what is known right now. */
    internal fun shouldCompress(request: Request): Boolean {
        if (!serverAccepts) return false
        val body = request.body ?: return false
        if (request.header("Content-Encoding") != null) return false
        val length = runCatching { body.contentLength() }.getOrDefault(-1L)
        // Unknown length is streamed and assumed big; a known small body is left alone.
        return length == -1L || length >= MIN_BYTES
    }

    /**
     * The body, gzipped as it streams.
     *
     * Content length becomes unknown — the compressed size cannot be known without compressing
     * twice — so the request goes out chunked, which every HTTP/1.1 server this SDK can reach
     * already handles.
     */
    internal fun gzipped(body: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = body.contentType()

        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            val gzip = GzipSink(sink).buffer()
            body.writeTo(gzip)
            gzip.close()
        }
    }

    /**
     * Both halves in one interceptor, added to every client the SDK builds: requests grow a gzip
     * body once the latch is up, and every response is read for the advertisement that raises it.
     */
    internal class WhenServerAccepts : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val sent = if (shouldCompress(request)) {
                request.newBuilder()
                    .header("Content-Encoding", "gzip")
                    .method(request.method, gzipped(request.body!!))
                    .build()
            } else {
                request
            }
            return chain.proceed(sent).also { noteResponse(it) }
        }
    }
}
