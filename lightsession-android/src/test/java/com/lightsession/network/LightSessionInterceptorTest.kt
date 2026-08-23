package com.lightsession.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The disciplines an interceptor in a customer's network path has to keep.
 *
 * This is the one part of the SDK whose worst failure is *their app* rather than a wrong number
 * on a page — a request that never completes, an exception the app has no reason to expect. So
 * what is tested here is not what it records; it is what it must never do while recording.
 *
 * Driven through a fake [Interceptor.Chain] rather than a real server: `Chain` is an interface,
 * the invariants are about control flow, and a test that needs a socket to prove "this does not
 * throw" is a test that can fail for a reason unrelated to the claim.
 */
class LightSessionInterceptorTest {

    // --------------------------------------------------------------- the chain

    private class FakeChain(
        private val request: Request,
        private val onProceed: (Request) -> Response,
    ) : Interceptor.Chain {
        val proceedCount = AtomicInteger(0)

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceedCount.incrementAndGet()
            return onProceed(request)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = OkHttpClient().newCall(request)
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun request(url: String = "https://api.example.com/v1/orders/84321"): Request =
        Request.Builder().url(url).build()

    private fun ok(request: Request, code: Int = 200, body: String = "{}"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    // ------------------------------------------------------------- one row per request

    /**
     * Two instances in one chain record once.
     *
     * Not hypothetical, and it is the failure mode a build-time transform makes likely: a
     * transform that inserts this interceptor cannot see that the source already installed it by
     * hand. Measured on the sample with both paths active before this guard existed — one request,
     * two rows, no error and no log. Every number the product shows would have been doubled.
     *
     * Asserted through the outer interceptor's own view: the inner one must still call `proceed`,
     * so the request reaches the network exactly once either way, and the recording is what
     * collapses.
     */
    @Test
    fun `a second instance in the same chain does not record again`() {
        val req = request()
        var innerSawTag = false
        var proceeds = 0

        // The inner interceptor stands where a second copy of this class would: it receives the
        // request the outer one already tagged.
        val inner = Interceptor { chain ->
            innerSawTag = chain.request().tag(AlreadyRecording::class.java) != null
            proceeds++
            LightSessionInterceptor().intercept(
                object : Interceptor.Chain {
                    override fun request(): Request = chain.request()
                    override fun proceed(request: Request): Response = ok(request)
                    override fun connection(): Connection? = null
                    override fun call(): Call = OkHttpClient().newCall(chain.request())
                    override fun connectTimeoutMillis(): Int = 0
                    override fun readTimeoutMillis(): Int = 0
                    override fun writeTimeoutMillis(): Int = 0
                    override fun withConnectTimeout(t: Int, u: TimeUnit) = this
                    override fun withReadTimeout(t: Int, u: TimeUnit) = this
                    override fun withWriteTimeout(t: Int, u: TimeUnit) = this
                },
            )
        }

        val outerChain = FakeChain(req) { tagged -> inner.intercept(SingleShot(tagged)) }
        LightSessionInterceptor().intercept(outerChain)

        assertTrue("the outer instance did not tag the request", innerSawTag)
        assertEquals("the request must still reach the network once", 1, proceeds)
    }

    /** A chain whose `proceed` just answers, for the nested case above. */
    private class SingleShot(private val request: Request) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()

        override fun connection(): Connection? = null
        override fun call(): Call = OkHttpClient().newCall(request)
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    // --------------------------------------------------------------- pass-through

    /** Whatever this class gets wrong, the app must see exactly what it would have seen. */
    @Test
    fun `the response is passed through untouched`() {
        val req = request()
        val expected = ok(req)
        val chain = FakeChain(req) { expected }

        val got = LightSessionInterceptor().intercept(chain)

        assertSame(expected, got)
        assertEquals(1, chain.proceedCount.get())
    }

    /**
     * A failure has to arrive at the app as the same exception. Wrapping it, or swallowing it and
     * inventing a response, turns a network error into a bug with our name on it.
     */
    @Test
    fun `an io failure is rethrown as itself`() {
        val req = request()
        val boom = IOException("connection reset")
        val chain = FakeChain(req) { throw boom }

        val thrown = runCatching { LightSessionInterceptor().intercept(chain) }.exceptionOrNull()

        assertSame(boom, thrown)
        assertEquals(1, chain.proceedCount.get())
    }

    /** Called once. A retry we did not ask for would double a payment. */
    @Test
    fun `proceed is called exactly once`() {
        val req = request()
        val chain = FakeChain(req) { ok(req) }
        LightSessionInterceptor().intercept(chain)
        assertEquals(1, chain.proceedCount.get())
    }

    // --------------------------------------------------------------- never throwing

    /**
     * The guard, exercised rather than asserted about. A `RequestBody` whose `contentLength()`
     * throws is a real shape — a streaming body that cannot answer — and it is read on the
     * recording path, which is inside the guard. Without the guard this reaches the app as an
     * `IOException` on a request that actually succeeded.
     */
    @Test
    fun `a failure while recording does not reach the app`() {
        val hostile = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = throw IllegalStateException("cannot answer")
            override fun writeTo(sink: BufferedSink) {}
        }
        val req = Request.Builder().url("https://api.example.com/v1/orders").post(hostile).build()
        val expected = ok(req)
        val chain = FakeChain(req) { expected }

        val got = LightSessionInterceptor().intercept(chain)

        assertSame(expected, got)
    }

    /** And the same on the failure path, where the exception is already on its way out. */
    @Test
    fun `a failure while recording does not replace the apps exception`() {
        val hostile = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = throw IllegalStateException("cannot answer")
            override fun writeTo(sink: BufferedSink) {}
        }
        val req = Request.Builder().url("https://api.example.com/v1/orders").post(hostile).build()
        val boom = IOException("timeout")
        val chain = FakeChain(req) { throw boom }

        val thrown = runCatching { LightSessionInterceptor().intercept(chain) }.exceptionOrNull()

        assertSame(boom, thrown)
    }

    // --------------------------------------------------------------- no lock across the call

    /**
     * Requests must not queue behind each other.
     *
     * `chain.proceed` is the slow part — seconds on a bad network — and a lock held across it
     * would serialise every request an app makes through this interceptor. That is not a
     * degraded measurement, it is a broken app, and it is invisible in a test that makes one
     * call at a time.
     *
     * Measured by making eight calls that each block until all eight have started. If anything
     * here serialises the call, the latch never opens and this times out.
     */
    @Test
    fun `concurrent requests are not serialised`() {
        val threads = 8
        val allStarted = CountDownLatch(threads)
        val interceptor = LightSessionInterceptor()
        val failures = AtomicInteger(0)

        val workers = (0 until threads).map { index ->
            Thread {
                val req = request("https://api.example.com/v1/items/$index")
                val chain = FakeChain(req) {
                    allStarted.countDown()
                    // Blocks until every other call is also inside `proceed`.
                    if (!allStarted.await(5, TimeUnit.SECONDS)) failures.incrementAndGet()
                    ok(req)
                }
                runCatching { interceptor.intercept(chain) }
                    .onFailure { failures.incrementAndGet() }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(10_000) }

        assertEquals(0, failures.get())
        assertTrue("a worker was still running: a lock is held across proceed",
            workers.none { it.isAlive })
    }

    // --------------------------------------------------------------- classifying a failure

    /**
     * A class, never a message. An `IOException`'s text carries the URL it failed on — query
     * string included — so `toString()` here would put back exactly what [PathTemplate] keeps
     * out.
     */
    @Test
    fun `a failure is classified by type and not by message`() {
        assertEquals("timeout", FailureClass.of(java.net.SocketTimeoutException("api.example.com/v1?token=x")))
        assertEquals("dns", FailureClass.of(java.net.UnknownHostException("api.example.com")))
        assertEquals("tls", FailureClass.of(javax.net.ssl.SSLHandshakeException("bad cert")))
        assertEquals("connect", FailureClass.of(java.net.ConnectException("refused")))
        assertEquals("io", FailureClass.of(IOException("something else")))
    }

    /** OkHttp wraps the real cause more often than not. */
    @Test
    fun `a wrapped cause is still classified`() {
        val wrapped = IOException("failed to connect", java.net.SocketTimeoutException("timeout"))
        assertEquals("timeout", FailureClass.of(wrapped))
    }
}
