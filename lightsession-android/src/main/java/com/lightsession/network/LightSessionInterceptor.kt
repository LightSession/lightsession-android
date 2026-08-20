package com.lightsession.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Records one row per HTTP request, on the screen that made it.
 *
 * ```kotlin
 * OkHttpClient.Builder()
 *     .addInterceptor(LightSessionInterceptor())
 *     .build()
 * ```
 *
 * ## Opt-in, and why that is the design rather than a limitation
 *
 * Every other thing this SDK does can, at worst, be wrong about the app. This one is *in the
 * path of the app's own network*, and its worst failure is not a wrong number on a page — it is
 * a request that never completes. A replay tool that breaks the app it is watching does not get
 * a second chance with that customer.
 *
 * So the customer installs it, on the client they own. We cannot be in a path they did not hand
 * us. The cost is stated rather than hidden: this sees nothing from a third-party SDK making its
 * own calls, and nothing from an HTTP client that is not OkHttp. "We support OkHttp" is honest;
 * "we capture your network" would not be.
 *
 * The alternatives were considered and are worse. `URL.setURLStreamHandlerFactory` is
 * process-global, can be set exactly once, and loses to whichever library sets it first — and it
 * misses OkHttp, which is what most apps actually use.
 *
 * ## The two disciplines
 *
 * **It cannot throw into the chain.** Every line except `chain.proceed` sits inside a `runCatching`,
 * and a failure inside this class is logged once and dropped. An interceptor that throws turns a
 * working request into an `IOException` the app has no reason to expect, and it would do it on
 * every request rather than once.
 *
 * **It holds no lock across the call.** `chain.proceed` is the slow part — seconds, on a bad
 * network — and nothing here is synchronized around it. The recording happens after the response
 * is in hand, and hands off to a queue rather than doing work on the caller's thread.
 *
 * ## What it reads
 *
 * Method, host, collapsed path, status, duration, and the byte counts the client already knows.
 * Not headers. Not bodies — `contentLength()` is a declared number, and reading a body here would
 * consume the stream the app is about to read, which is the classic way an interceptor breaks the
 * app it is measuring. An unknown length is reported as 0 rather than guessed at.
 *
 * The path is collapsed here, on the device — see [PathTemplate].
 */
public class LightSessionInterceptor : Interceptor {

    @Throws(IOException::class)
    public override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()

        // Outside every guard below, called exactly once, and its outcome is passed through
        // untouched. Whatever this class gets wrong, it must not change what the app sees.
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (failure: IOException) {
            record(request, startedAt, status = 0, responseBytes = 0, failure = failure)
            throw failure
        }

        record(request, startedAt, response.code, response.body?.contentLength() ?: -1, null)
        return response
    }

    private fun record(
        request: okhttp3.Request,
        startedAt: Long,
        status: Int,
        responseBytes: Long,
        failure: IOException?,
    ) {
        runCatching {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            NetworkRecorder.record(
                method = request.method,
                host = request.url.host,
                path = PathTemplate.of(request.url.encodedPath),
                status = status,
                durationMs = durationMs,
                requestBytes = request.body?.contentLength() ?: -1,
                responseBytes = responseBytes,
                errorClass = failure?.let { FailureClass.of(it) } ?: "",
            )
        }.onFailure {
            // Once, at debug. A network problem is the moment an app's log is being read, and a
            // line of ours per request in the middle of it helps nobody.
            Log.d("LightSession.Network", "request not recorded: ${it.javaClass.simpleName}")
        }
    }
}

/**
 * A word for why a request never got a status.
 *
 * A *class*, never a message, and the distinction is the point: an `IOException`'s message
 * carries the URL it failed on — query string included — so `toString()` here would put back
 * exactly what [PathTemplate] exists to keep out. Read from the exception's type alone.
 *
 * The names are the ones a reader groups by, not the ones Java uses: `SocketTimeoutException`
 * and a timeout inside a TLS handshake are both `timeout` to somebody asking why a screen is
 * slow.
 */
internal object FailureClass {
    fun of(failure: IOException): String {
        // Walked type by type rather than by name, so an obfuscated build still classifies —
        // the same reasoning the mapper uses for View classes under R8.
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is java.net.SocketTimeoutException -> return "timeout"
                is java.net.UnknownHostException -> return "dns"
                is javax.net.ssl.SSLException -> return "tls"
                is java.net.ConnectException -> return "connect"
                is java.net.SocketException -> return "connection_lost"
            }
            if (current.javaClass.simpleName == "StreamResetException") return "cancelled"
            current = current.cause
        }
        return "io"
    }
}
