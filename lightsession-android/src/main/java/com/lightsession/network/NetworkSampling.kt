package com.lightsession.network

/**
 * Whether a request is recorded, and what it stands for if it is.
 *
 * ## Why the session and not the request
 *
 * A coin per request is the obvious design and it is wrong here. At a tenth, a screen that fires
 * six calls at once is recorded as one, and a reader concludes the screen makes one request. That
 * is a lie about the app's *structure*, not merely about its volume, and no sample size fixes it.
 * A session's timeline gets the same treatment — holes in it, which is the one view this product
 * has that a server-side tool does not.
 *
 * So the unit is the session: recorded whole, or not recorded. Structure survives, timelines
 * survive, and the statistics are still a uniform sample because sessions are drawn uniformly.
 *
 * ## Why a hash and not a coin
 *
 * The decision is derived from the session id rather than rolled and remembered. Nothing to
 * store, nothing to reset, and no rotation hook to forget — when the session id changes the
 * answer changes with it, which is what keeps the sample uniform across a long-lived process
 * instead of committing it to one verdict at launch. It is also a pure function, so it is
 * testable without a session.
 *
 * ## Why failures are kept anyway
 *
 * A rare failure at a tenth is seen once in ten occurrences, and the failure somebody phones
 * about is exactly the rare one. So a request that failed is recorded even in a session that was
 * not sampled — with weight `0`, which the server reads as "list it, count it nowhere". Without
 * that weight these extras would be a census of failures beside a sample of successes and every
 * rate they touched would be wrong by the sampling factor.
 */
internal object NetworkSampling {

    /** Off. Every request is recorded and stands for exactly itself. */
    const val NO_SAMPLING: Double = 1.0

    /**
     * Below this a rate is treated as "record nothing but failures". Not zero, because a
     * `1e-9` is a caller asking for nothing by a route that would otherwise divide into a
     * weight no column can hold.
     */
    private const val MIN_RATE: Double = 0.0001

    /**
     * What one recorded request stands for, or `null` when it is not recorded at all.
     *
     * The three answers, and each is a different statement to the server:
     *  - `1`  — no sampling, or a sampled-in session at full rate. Stands for itself.
     *  - `N`  — a sampled-in session at rate `1/N`. Stands for `N` requests.
     *  - `0`  — a failure from a session that was not sampled. Visible, counted nowhere.
     *  - null — a success from a session that was not sampled. Not recorded.
     */
    fun weightFor(sessionId: String, rate: Double, failed: Boolean): Int? {
        // A rate that is not a rate is a caller mistake, and the safe reading of a mistake here
        // is to record everything: too much data is a cost, too little is a wrong answer.
        if (rate.isNaN() || rate >= NO_SAMPLING) return 1
        if (rate < MIN_RATE) return if (failed) 0 else null

        return if (inSample(sessionId, rate)) {
            // Rounded, and at least 1. The weight is a count of real requests and cannot be
            // fractional; a rate above 0.5 rounds to 1, which under-counts slightly rather than
            // claiming traffic that did not happen.
            (1.0 / rate).roundToIntAtLeastOne()
        } else if (failed) {
            0
        } else {
            null
        }
    }

    /**
     * Whether this session is one of the recorded ones.
     *
     * `10_000` buckets, so a rate is honoured to a hundredth of a percent — the same floor
     * [MIN_RATE] sets, and past it the arithmetic would be claiming precision the bucketing
     * does not have.
     */
    fun inSample(sessionId: String, rate: Double): Boolean {
        if (rate.isNaN() || rate >= NO_SAMPLING) return true
        if (rate < MIN_RATE) return false
        return (hash(sessionId) % 10_000u) < (rate * 10_000).toUInt()
    }

    /**
     * FNV-1a over the id's UTF-8 bytes.
     *
     * Written out rather than using `String.hashCode`, and the reason is a bug this codebase has
     * already paid for once in [com.lightsession.mapper.ScreenIdentity]: a platform string hash
     * is not a promise. It can be seeded per process, and a per-process answer would mean a
     * session sampled in on one launch and out on the next — the spool carries a session across
     * launches, so those two verdicts would end up in the same session's data.
     */
    private fun hash(value: String): UInt {
        var h = 2166136261u
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            h = h xor (byte.toUInt() and 0xFFu)
            h *= 16777619u
        }
        return h
    }

    private fun Double.roundToIntAtLeastOne(): Int {
        val rounded = Math.round(this)
        return if (rounded < 1L) 1 else if (rounded > Int.MAX_VALUE) Int.MAX_VALUE else rounded.toInt()
    }
}
