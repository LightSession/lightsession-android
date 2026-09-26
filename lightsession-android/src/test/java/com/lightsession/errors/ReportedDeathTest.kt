package com.lightsession.errors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a native crash is the death an embedder already reported, and when it is a crash of its own.
 */
class ReportedDeathTest {

    private var now = 1_000_000L
    private val death = ReportedDeath { now }

    @Test
    fun `nothing reported covers nothing`() {
        assertFalse(death.covers())
    }

    /** Measured: the `JavascriptException` came 2 ms after the report from a handler, 15 ms from a render. */
    @Test
    fun `a native crash right after the report is the same death`() {
        death.record()
        now += 15
        assertTrue(death.covers())
        now = 1_000_000L + ReportedDeath.WINDOW_MS
        assertTrue(death.covers())
    }

    /** An app that kept running after the crash it reported: a later crash is its own. */
    @Test
    fun `the report lapses`() {
        death.record()
        now += ReportedDeath.WINDOW_MS + 1
        assertFalse(death.covers())
    }

    /** A clock that ran backwards says nothing about the order of the two. */
    @Test
    fun `a crash timed before the report is not covered`() {
        death.record()
        now -= 1
        assertFalse(death.covers())
    }
}
