package com.lightsession.mapper

/**
 * Which tabs were already selected when the current destination was entered.
 *
 * The tab reader cannot tell a bottom-nav item from a screen's own tab row — both report
 * `Role.Tab` and semantics does not distinguish them. What distinguishes them is time: the
 * nav item is a function of the destination and is already selected on arrival, so whatever
 * is selected *now* and was not selected *then* is the tab the person chose. That makes the
 * arrival state load-bearing, and this class exists because losing it was a measured bug.
 *
 * The loss was a scheduling race. The arrival read and the gesture read shared one delayed
 * runnable, and scheduling either cancelled the other — so a tap landing inside the settle
 * window cancelled the arrival read outright. The next read then diffed against an empty
 * baseline, found the destination's own nav label "new", and minted a phantom screen:
 * `feed › Timeline`, where Timeline is feed's own label. Its fingerprint in production was
 * exact: kind UNKNOWN, no capture, one interaction, alive for about the settle window. The
 * redundancy fold cannot catch it afterwards, because the route id and the visible label
 * share no characters — `home › Home` folds, `feed › Timeline` cannot.
 *
 * So learning is decoupled from scheduling. Whatever fires first after a destination change
 * — the arrival read or a gesture's — the first read *is* the baseline, and only reads
 * after it may choose. A tap inside the settle window now costs at most one unreported tab
 * switch, absorbed into the arrival state; the alternative was a permanent screen that does
 * not exist. Transient loss over permanent noise.
 *
 * Learning completes only on a non-empty read. The arrival read is delayed because the
 * NavController reports the destination before its content composes — but composition can
 * outlast the delay, and an empty read taken then would freeze an empty baseline and leave
 * every later read one diff away from the same phantom. An empty read teaches nothing about
 * a screen with tabs and changes nothing on a screen without them, so it keeps the learning
 * window open instead of closing it.
 */
internal class TabBaseline {

    /** The tabs selected on arrival. Meaningful once [learned]; empty until then. */
    var defaults: List<String> = emptyList()
        private set

    /** Whether the arrival state of the current destination is known yet. */
    var learned: Boolean = false
        private set

    /** A navigation landed: the previous destination's arrival state means nothing here. */
    fun reset() {
        defaults = emptyList()
        learned = false
    }

    /** Record [tabs] as the arrival state; see the class doc for why empty keeps learning. */
    fun learn(tabs: List<String>) {
        defaults = tabs
        if (tabs.isNotEmpty()) learned = true
    }

    /** The selected tab that was not selected on arrival, or null when nothing moved. */
    fun choose(tabs: List<String>): String? = tabs.firstOrNull { it !in defaults }
}
