package com.lightsession.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the URL-taking entry point keeps and what it refuses.
 *
 * The parsing is asserted through [PathTemplate] rather than through the recorder, because the
 * recorder needs a session and this is about the URL. What matters here is the pairing: the host
 * comes out clean and the path comes out collapsed, from one string, without the caller having to
 * split it — which is the whole reason the entry point exists.
 */
class RecordUrlTest {

    private fun hostAndPath(url: String): Pair<String?, String> {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
        val host = parsed?.host
        return host to PathTemplate.of(parsed?.rawPath ?: "")
    }

    @Test
    fun `a url becomes a clean host and a collapsed path`() {
        assertEquals(
            "api.example.com" to "/v1/orders/{id}/items",
            hostAndPath("https://api.example.com/v1/orders/84321/items"),
        )
    }

    /**
     * The reason a caller must not be asked to pass a path. A query carries tokens, and this entry
     * point is reached from JavaScript where the URL arrives whole.
     */
    @Test
    fun `the query never reaches the path`() {
        val (host, path) = hostAndPath(
            "https://api.example.com/v1/me?token=eyJhbGciOiJIUzI1NiJ9&cpf=12345678900",
        )
        assertEquals("api.example.com", host)
        assertEquals("/v1/me", path)
        assertEquals(false, path.contains("eyJ"))
        assertEquals(false, path.contains("12345678900"))
    }

    /**
     * `rawPath`, not `path`. The decoded form turns a `%2F` inside a segment into a separator and
     * invents a path level the request never had — one endpoint reported as two.
     */
    @Test
    fun `an encoded slash stays inside its segment`() {
        val (_, path) = hostAndPath("https://h.com/v1/files/a%2Fb")
        assertEquals("/v1/files/{id}", path)
    }

    @Test
    fun `a url with no usable path is refused rather than guessed at`() {
        for (url in listOf("not a url", "https://h.com", "", "://")) {
            val (_, path) = hostAndPath(url)
            assertEquals("for $url", "", path)
        }
    }
}
