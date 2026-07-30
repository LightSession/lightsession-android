package com.sample.lightsession

/**
 * The migration shape: a `NavHostFragment` whose second destination renders with Compose.
 *
 * The same Activity as [FragmentNavActivity] with a different graph, because the difference between
 * the two is exactly one thing — whether a `ComposeView` exists somewhere under the Activity — and
 * that one thing used to decide whether *any* screen was recorded.
 *
 * What went wrong: a fragment's views are attached under the Activity's content view, so once the
 * Compose destination is showing, "this Activity uses Compose" is true for the Activity. The mapper
 * tested that first and short-circuited the fragment registration, so neither the destinations nor
 * the Activity name were reported. An app mid-migration lost the navigation it already had.
 *
 * It was also timing-dependent, which is the part worth reproducing by hand: the check runs once, at
 * resume. Open this screen, navigate to the Compose destination, background the app, come back — the
 * Activity resumed with Compose on screen. Do the same from the XML destination and it resumed
 * without. Two behaviours from one build, and neither was a crash.
 *
 * Expected in the map now: `hybridXml` and `hybridCompose` as screens, with an edge between them,
 * whichever one you resumed on.
 */
class HybridNavActivity : FragmentNavActivity() {
    override val graphResId: Int = R.navigation.nav_hybrid
}
