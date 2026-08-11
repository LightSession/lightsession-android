package com.lightsession

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.CacheManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bar that decides whether a wireframe is worth resending.
 *
 * The map's memory of a screen used to be one boolean — sent, or not — so the first wireframe out
 * was the screen's picture forever. That is wrong for exactly the screens that matter: a loading
 * screen's first capture is a spinner in a shell, and if the late-content watch was cancelled by a
 * touch on that visit, the spinner was permanent. The ratchet replaces the boolean with a count,
 * and a later capture ships only when it clears the count.
 *
 * These are the properties the convergence rests on, tested directly rather than inferred from the
 * three-launch behaviour they produce.
 */
@RunWith(AndroidJUnit4::class)
class WireframeRatchetTest {

    private lateinit var cache: CacheManager

    private companion object {
        var keySeq = 0
    }

    @Before
    fun setUp() {
        cache = CacheManager(ApplicationProvider.getApplicationContext())
        // The store is one install-scoped file and the bar never falls, so a previous run's
        // maximum would survive into this one. Wiped here rather than worked around with unique
        // keys, which is the honest reset for a cache whose whole contract is that it does not
        // reset on its own.
        cache.clearAllCache()
    }

    // Global to the class, not the instance: JUnit builds a fresh instance per method, so a
    // per-instance counter hands every test the same first key. A companion counter keeps every
    // key this class ever asks for distinct, on top of the wipe in setUp.
    private fun key() = "ratchet-${keySeq++}"

    /** Unknown is zero, and zero is what makes any real capture "richer" on a legacy install. */
    @Test
    fun an_unseen_screen_reports_zero() {
        assertEquals(0, cache.wireframeRects(key()))
    }

    /** The bar only rises. A poorer later capture — the spinner, seen again — is not a regression. */
    @Test
    fun the_bar_never_falls() {
        val k = key()
        cache.recordWireframeRects(k, 81)
        cache.recordWireframeRects(k, 37) // the spinner, revisited
        assertEquals("a poorer capture lowered the bar", 81, cache.wireframeRects(k))
    }

    /** Equal is not richer: a screen redrawn identically must not resend, or it never goes quiet. */
    @Test
    fun an_equal_capture_does_not_raise_the_bar() {
        val k = key()
        cache.recordWireframeRects(k, 50)
        cache.recordWireframeRects(k, 50)
        assertEquals(50, cache.wireframeRects(k))
    }

    /** The heal: a spinner recorded first, the loaded screen clearing it once. */
    @Test
    fun a_richer_capture_raises_the_bar() {
        val k = key()
        cache.recordWireframeRects(k, 37)
        assertEquals(37, cache.wireframeRects(k))
        cache.recordWireframeRects(k, 81)
        assertEquals(81, cache.wireframeRects(k))
    }

    /** Two screens do not share a bar — a substring of one key must not match another. */
    @Test
    fun bars_are_per_screen() {
        val a = key()
        val b = key()
        cache.recordWireframeRects(a, 81)
        cache.recordWireframeRects(b, 20)
        assertEquals(81, cache.wireframeRects(a))
        assertEquals(20, cache.wireframeRects(b))
    }
}
