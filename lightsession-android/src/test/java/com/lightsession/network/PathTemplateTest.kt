package com.lightsession.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What may leave the device inside an endpoint name.
 *
 * The two failures are not symmetric, and the tests are grouped by which one they guard.
 * Over-collapsing merges two endpoints and a reader can see it happened. Under-collapsing puts
 * a token in a column with a thirteen-month TTL and mints one endpoint per value, which is
 * invisible until someone reads the table and cannot be undone.
 */
class PathTemplateTest {

    // --------------------------------------------- what must never survive

    @Test
    fun `a query string is not part of the path`() {
        assertEquals("/v1/session", PathTemplate.of("/v1/session?token=eyJhbGciOiJIUzI1NiJ9"))
        assertEquals("/v1/me", PathTemplate.of("/v1/me?api_key=sk_live_4eC39Hq&page=2"))
    }

    @Test
    fun `a fragment is not part of the path`() {
        assertEquals("/callback", PathTemplate.of("/callback#access_token=ya29.a0AfH6"))
    }

    @Test
    fun `a numeric id is collapsed`() {
        assertEquals("/v1/users/{id}/orders", PathTemplate.of("/v1/users/84321/orders"))
    }

    @Test
    fun `a uuid is collapsed and says so`() {
        assertEquals(
            "/v1/orders/{uuid}",
            PathTemplate.of("/v1/orders/550e8400-e29b-41d4-a716-446655440000"),
        )
    }

    /**
     * The one that matters most. A signed token in a path segment is both the leak and the
     * cardinality explosion, and it is long.
     */
    @Test
    fun `a token in a path segment is collapsed`() {
        assertEquals(
            "/v1/invite/{id}/accept",
            PathTemplate.of("/v1/invite/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9/accept"),
        )
    }

    @Test
    fun `a hex hash is collapsed`() {
        assertEquals("/files/{id}", PathTemplate.of("/files/9f86d081884c7d659a2feaa0"))
    }

    /**
     * A segment that is not written in the alphabet a route is written in is data — a
     * percent-encoded slash, a comma-joined list of ids, base64 padding.
     */
    @Test
    fun `a segment outside a routes alphabet is collapsed`() {
        assertEquals("/v1/items/{id}", PathTemplate.of("/v1/items/1,2,3"))
        assertEquals("/v1/{id}/x", PathTemplate.of("/v1/a%2Fb/x"))
        assertEquals("/v1/{id}", PathTemplate.of("/v1/dGVzdA=="))
    }

    /** An email or a phone number as a path segment is the worst case of all. */
    @Test
    fun `personal data as a segment is collapsed`() {
        assertEquals("/v1/users/{id}", PathTemplate.of("/v1/users/maria@example.com"))
        assertEquals("/v1/users/{id}", PathTemplate.of("/v1/users/+5511990000001"))
    }

    // --------------------------------------------- what must survive

    /**
     * The other half: collapsing everything would be safe and useless. An endpoint list needs
     * to name endpoints.
     */
    @Test
    fun `route words are kept`() {
        assertEquals("/v1/orders", PathTemplate.of("/v1/orders"))
        assertEquals("/api/v2/users/me", PathTemplate.of("/api/v2/users/me"))
        assertEquals("/checkout-summary", PathTemplate.of("/checkout-summary"))
        assertEquals("/oauth2/token", PathTemplate.of("/oauth2/token"))
    }

    @Test
    fun `the root is a path`() {
        assertEquals("/", PathTemplate.of("/"))
    }

    /** A trailing slash is a different route to some servers; it is not this class's job to decide. */
    @Test
    fun `a trailing slash is preserved`() {
        assertEquals("/v1/orders/", PathTemplate.of("/v1/orders/"))
    }

    /**
     * An extension is not data, and keeping it keeps two different questions apart: how often an
     * asset path is called, versus which asset.
     */
    @Test
    fun `a file extension survives while the name collapses`() {
        assertEquals("/assets/logo.png", PathTemplate.of("/assets/logo.png"))
        assertEquals("/assets/{id}.png", PathTemplate.of("/assets/9f86d081884c7d659a2f.png"))
    }

    /**
     * An id wearing an extension is still an id. The stem used to skip the digit and UUID rules, so
     * a numbered or UUID-named file published its name — and the quiet half of that failure is one
     * endpoint per file, which is the endpoint list destroying itself.
     */
    @Test
    fun `an id with a file extension on it is still an id`() {
        assertEquals("/assets/{id}.png", PathTemplate.of("/assets/8842.png"))
        assertEquals(
            "/f/{uuid}.pdf",
            PathTemplate.of("/f/1e527025-c3ae-40c1-bf98-7d6a67e759a6.pdf"),
        )
        assertEquals("/f/report.tar.gz", PathTemplate.of("/f/report.tar.gz"))
    }

    // --------------------------------------------- refusing rather than guessing

    @Test
    fun `something that is not a path is refused rather than guessed at`() {
        assertEquals("", PathTemplate.of(""))
        assertEquals("", PathTemplate.of("   "))
        assertEquals("", PathTemplate.of("v1/orders"))
        assertEquals("", PathTemplate.of("https://api.example.com/v1/orders"))
    }

    /** A template this long means the collapsing did not recognise what it was given. */
    @Test
    fun `an absurdly long path is refused`() {
        val deep = "/a".repeat(150)
        assertEquals("", PathTemplate.of(deep))
    }
}
