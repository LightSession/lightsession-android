package com.sample.lightsession

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment

/**
 * The conventional Navigation shape: a `NavHostFragment` with View-based destinations.
 *
 * Needs no integration code. The nav host is inflated with the layout, so the SDK reaches its
 * NavController through the FragmentManager and listens for destination changes — the opposite of a
 * Compose NavController, which is created inside a composition and can only be handed over.
 *
 * Expected in the map: `fragmentOne` and `fragmentTwo`, with an edge between them. The Activity's own
 * name should **not** appear — the destinations are the screens, and a node for the Activity beside
 * them would be a screen no user ever saw.
 *
 * This path had no coverage in the sample until now, which is how it went nineteen commits without
 * being run.
 */
open class FragmentNavActivity : AppCompatActivity() {

    /** The graph to host. Overridden by [HybridNavActivity], which is the same shape plus Compose. */
    protected open val graphResId: Int = R.navigation.nav_fragments

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)

        // `commitNow`, and only on a fresh start. Asynchronous commit would let `onResume` run
        // before the host is in the FragmentManager, and the mapper's check happens at resume —
        // it would find no nav host and treat the Activity as a screen of its own. Recreating on
        // rotation must not add a second host, hence the saved-state guard.
        if (savedInstanceState == null) {
            val host = NavHostFragment.create(graphResId)
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_container, host)
                .setPrimaryNavigationFragment(host)
                .commitNow()
        }
    }
}
