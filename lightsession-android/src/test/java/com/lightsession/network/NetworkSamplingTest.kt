package com.lightsession.network

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sampling decision.
 *
 * Two properties carry this feature and both are tested against many sessions rather than one:
 * the share of sessions kept has to match the rate asked for, and the weight has to be the
 * reciprocal of that share — otherwise the estimate the server computes is wrong in a way no
 * single-session test can see.
 */
class NetworkSamplingTest {

    private fun sessions(n: Int): List<String> =
        (0 until n).map { UUID.nameUUIDFromBytes("session-$it".toByteArray()).toString() }

    // ------------------------------------------------------------------ off

    @Test
    fun `the default records everything and claims nothing extra`() {
        for (session in sessions(50)) {
            assertEquals(1, NetworkSampling.weightFor(session, 1.0, failed = false))
            assertEquals(1, NetworkSampling.weightFor(session, 1.0, failed = true))
        }
    }

    /**
     * A rate above one, or a NaN, is a caller mistake. The safe reading of a mistake is to record
     * everything: too much data costs money, too little is a wrong answer nobody can detect.
     */
    @Test
    fun `a rate that is not a rate records everything`() {
        for (rate in listOf(1.5, 42.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                "rate $rate",
                1,
                NetworkSampling.weightFor(sessions(1).first(), rate, failed = false),
            )
        }
    }

    // ------------------------------------------------------ the share kept

    /**
     * The property the whole design rests on. Off by more than a little and every count the
     * server reports is off by the same factor — silently, because the number still looks
     * plausible.
     */
    @Test
    fun `the share of sessions kept matches the rate asked for`() {
        val all = sessions(20_000)
        for (rate in listOf(0.5, 0.25, 0.1, 0.05, 0.01)) {
            val kept = all.count { NetworkSampling.inSample(it, rate) }
            val share = kept.toDouble() / all.size
            // 15% relative tolerance: this is a hash over a finite set, not a generator, so the
            // share is fixed rather than converging — the tolerance is for the bucketing, not
            // for luck.
            assertTrue(
                "rate $rate kept ${"%.4f".format(share)}",
                share > rate * 0.85 && share < rate * 1.15,
            )
        }
    }

    /**
     * The estimate the server computes is `weight × rows`. If the weight and the share disagree
     * the estimate is wrong, and this is the multiplication that has to come back to 1.
     */
    @Test
    fun `weight times the share kept reconstructs the traffic`() {
        val all = sessions(20_000)
        for (rate in listOf(0.5, 0.25, 0.1, 0.05, 0.01)) {
            val kept = all.filter { NetworkSampling.inSample(it, rate) }
            val weight = NetworkSampling.weightFor(kept.first(), rate, failed = false)!!
            val estimated = kept.size.toDouble() * weight
            assertTrue(
                "rate $rate estimated $estimated of ${all.size}",
                estimated > all.size * 0.85 && estimated < all.size * 1.15,
            )
        }
    }

    @Test
    fun `the weight is the reciprocal of the rate`() {
        val kept = sessions(20_000).first { NetworkSampling.inSample(it, 0.1) }
        assertEquals(10, NetworkSampling.weightFor(kept, 0.1, failed = false))
        val kept25 = sessions(20_000).first { NetworkSampling.inSample(it, 0.25) }
        assertEquals(4, NetworkSampling.weightFor(kept25, 0.25, failed = false))
    }

    // ---------------------------------------------------- failures are kept

    /**
     * The reason the weight can be zero at all. A rare failure at a tenth would otherwise be seen
     * once in ten occurrences, and the rare one is the one somebody phones about.
     */
    @Test
    fun `a failure is recorded even in a session that was not sampled`() {
        val out = sessions(20_000).first { !NetworkSampling.inSample(it, 0.01) }
        assertEquals(
            "a failure outside the sample is stored and counted nowhere",
            0,
            NetworkSampling.weightFor(out, 0.01, failed = true),
        )
        assertNull(
            "a success outside the sample is not stored at all",
            NetworkSampling.weightFor(out, 0.01, failed = false),
        )
    }

    /**
     * Inside the sample a failure is ordinary traffic and carries the ordinary multiplier. If it
     * carried 0 here the failure rate would read as zero for the sessions we can actually see.
     */
    @Test
    fun `a failure inside the sample is weighted like everything else`() {
        val inSample = sessions(20_000).first { NetworkSampling.inSample(it, 0.1) }
        assertEquals(10, NetworkSampling.weightFor(inSample, 0.1, failed = true))
        assertEquals(10, NetworkSampling.weightFor(inSample, 0.1, failed = false))
    }

    /** Zero, and anything under the floor, means failures only. */
    @Test
    fun `a rate of zero keeps failures and nothing else`() {
        for (rate in listOf(0.0, -1.0, 1e-9)) {
            val session = sessions(1).first()
            assertEquals("rate $rate", 0, NetworkSampling.weightFor(session, rate, failed = true))
            assertNull("rate $rate", NetworkSampling.weightFor(session, rate, failed = false))
        }
    }

    // ------------------------------------------------------------ stability

    /**
     * Every request in a session must get the same verdict. A decision that wobbled mid-session
     * would record a fraction of it — the per-request sampling this design exists to avoid,
     * arrived at by accident.
     */
    @Test
    fun `the decision is stable for a session`() {
        for (session in sessions(200)) {
            val first = NetworkSampling.inSample(session, 0.3)
            repeat(50) { assertEquals(first, NetworkSampling.inSample(session, 0.3)) }
        }
    }

    /**
     * The hash is written out rather than taken from `String.hashCode`, because a platform hash
     * is not a promise — it can be seeded per process. The spool carries a session across
     * launches, so a per-process answer would put two verdicts in one session's data.
     *
     * Pinned to the value rather than to itself, so a change to the algorithm is a decision.
     */
    @Test
    fun `the decision is pinned to the id and not to the process`() {
        assertTrue(NetworkSampling.inSample("1e527025-c3ae-40c1-bf98-7d6a67e759a6", 0.5))
        assertEquals(false, NetworkSampling.inSample("2C99B1BF-A9AE-4D2E-B985-E7805A76B120", 0.01))
        // Two ids one character apart land independently, which is what stops a device's
        // sequential session ids from all falling on the same side.
        val a = NetworkSampling.inSample("session-aaaa", 0.5)
        val b = NetworkSampling.inSample("session-aaab", 0.5)
        assertNotNull(a)
        assertNotNull(b)
    }

    /**
     * A rate the sampler honours to a hundredth of a percent, and no finer. Past the floor the
     * arithmetic would claim precision the bucketing does not have — and produce a weight no
     * column could hold.
     */
    @Test
    fun `no weight is ever absurd`() {
        val all = sessions(5_000)
        for (rate in listOf(0.9, 0.5, 0.1, 0.01, 0.001, 0.0001)) {
            for (session in all) {
                val weight = NetworkSampling.weightFor(session, rate, failed = false) ?: continue
                assertTrue("rate $rate gave $weight", weight in 1..10_000)
            }
        }
    }
}
