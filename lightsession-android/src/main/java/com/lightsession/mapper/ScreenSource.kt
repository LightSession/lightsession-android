package com.lightsession.mapper

/**
 * What supplies a screen's name for one Activity, decided as data rather than as control flow.
 *
 * ## Why this is a function and not an `if`
 *
 * It was an `if`, nested three deep inside `onActivityResumed`, and it was wrong for nineteen
 * commits without anything failing. Two faults lived in it:
 *
 *  * A Compose Activity with no NavController reported **nothing**. The Compose test came first and
 *    short-circuited the only branch that reports an Activity by name, on the assumption that a
 *    Compose host always has a NavController to hand over. One Activity holding one Compose screen
 *    has none, and is the most ordinary shape there is.
 *  * An Activity with **both** a `NavHostFragment` and a `ComposeView` registered neither. The same
 *    short-circuit skipped the fragment registration entirely — and since a fragment's views hang
 *    under the Activity's content view, one Compose screen anywhere in a migrating app was enough
 *    to make the check true. Worse, it is evaluated once at resume, so which path an Activity took
 *    depended on which fragment happened to be on screen when it resumed. The same build behaved
 *    two ways.
 *
 * Neither was visible from reading the branches, and neither could be tested: the conditions come
 * from a live view hierarchy and a FragmentManager. Reduced to two booleans, the whole space is four
 * cases and a plain JUnit test can walk all of them.
 */
internal data class ScreenSourcePlan(
    /**
     * Attach listeners to this Activity's `NavHostFragment` controllers now.
     *
     * Synchronous, because a conventional nav host is inflated with the layout — unlike a Compose
     * NavController, which is created inside a composition and can only be handed over.
     */
    val registerConventionalNav: Boolean,
    /**
     * Wait out the grace period, then take Compose destinations if a controller arrived and the
     * Activity's own name if none did.
     *
     * Deferred because nothing distinguishes "one Compose screen, nothing to hand over" from
     * "several Compose screens, the app has not handed the controller over yet" at resume time.
     */
    val resolveComposeAfterGrace: Boolean,
    /** Report the Activity's own name, now. Nothing else can name this screen. */
    val reportActivityNow: Boolean,
) {
    init {
        // Two reports would put a node per Activity in the map *and* a node per screen. Screens are
        // permanent, so a wrong node stays wrong — this is the invariant worth failing loudly on.
        require(!(resolveComposeAfterGrace && reportActivityNow)) {
            "an Activity cannot both defer to Compose and report its own name"
        }
    }
}

/**
 * Decides where one Activity's screen names come from.
 *
 * Deliberately takes booleans rather than an `Activity`: the two questions are answered by poking a
 * live view hierarchy and a FragmentManager, and mixing that in here is what made the old branching
 * untestable.
 *
 * @param usesCompose whether any `ComposeView` sits under the Activity's content view.
 * @param hasConventionalNavHost whether it hosts a `NavHostFragment`.
 */
internal fun planScreenSource(
    usesCompose: Boolean,
    hasConventionalNavHost: Boolean,
): ScreenSourcePlan = when {
    // A conventional nav host wins outright, Compose present or not. Its destinations are the
    // screens, and a Compose screen rendered *inside* one of them is part of that destination
    // rather than a screen of its own — so the Activity's name must not be reported alongside, and
    // the Compose path has nothing left to decide.
    hasConventionalNavHost -> ScreenSourcePlan(
        registerConventionalNav = true,
        resolveComposeAfterGrace = false,
        reportActivityNow = false,
    )

    // Compose with no fragment nav host: could be one screen or several. Only time tells.
    usesCompose -> ScreenSourcePlan(
        registerConventionalNav = false,
        resolveComposeAfterGrace = true,
        reportActivityNow = false,
    )

    // Neither. The Activity is the screen, which is also the answer for a legacy Activity that
    // predates AndroidX — those used to fall through a `activity is ComponentActivity` gate and be
    // reported as nothing at all.
    else -> ScreenSourcePlan(
        registerConventionalNav = false,
        resolveComposeAfterGrace = false,
        reportActivityNow = true,
    )
}
