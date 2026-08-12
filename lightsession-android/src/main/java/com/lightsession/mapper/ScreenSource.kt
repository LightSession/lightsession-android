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

/** Why an Activity did or did not become a node in the screen map. See [judgeActivityAsScreen]. */
internal enum class ActivityScreenVerdict {
    /** Nothing else can name it, so its own name is the screen. */
    IS_A_SCREEN,

    /** `screensReportedByHost` is on: no Activity is ever a screen in this app. */
    HOST_REPORTS_ALL,

    /** The flag is off, but the host has named a screen while this Activity was in front. */
    HOST_NAMED_THIS_ONE,
}

/**
 * Whether an Activity is a screen, or only the box that other screens are drawn in.
 *
 * Separate from [planScreenSource] because it is answered at a different moment. The plan is fixed
 * when an Activity resumes; this is asked when the Activity is about to be written into the map,
 * which for a Compose host is a grace period later — after the composition that calls `setScreen`
 * has run. Deciding it early is what left Compose Multiplatform out in the cold: a CMP app is one
 * Activity whose composition the SDK can happily see into, so its wireframes and heatmaps come out
 * right and nothing looks wrong, while the map quietly carries a permanent `MainActivity` node
 * beside the real screens.
 *
 * [hostNamed] is compared by identity and not by name, and that is the whole reason this returns a
 * verdict rather than the boolean it started as. The tempting version — a process-wide "the host
 * has spoken" flag — is wrong for the mixed app: a native app that hand-names one WebView screen
 * would have every *other* Activity silently stop being recorded. Per Activity, only the one the
 * host actually spoke for stands down, which is no more than `setScreen` was claiming anyway.
 *
 * @param hostNamed the Activity in front the last time the host reported a screen, or null if it
 *   never has or has since been collected. Typed as [Any] for the same reason [planScreenSource]
 *   takes booleans rather than an `Activity`: what matters here is identity, and the objects
 *   themselves cannot be built in a JVM test.
 */
internal fun judgeActivityAsScreen(
    screensReportedByHost: Boolean,
    hostNamed: Any?,
    activity: Any,
): ActivityScreenVerdict = when {
    screensReportedByHost -> ActivityScreenVerdict.HOST_REPORTS_ALL
    hostNamed === activity -> ActivityScreenVerdict.HOST_NAMED_THIS_ONE
    else -> ActivityScreenVerdict.IS_A_SCREEN
}
