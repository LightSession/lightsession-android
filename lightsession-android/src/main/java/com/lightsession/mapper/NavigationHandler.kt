package com.lightsession.mapper

import android.app.Activity
import androidx.navigation.NavDestination

interface NavigationHandler {
    /**
     * Handles navigation for Activities that don't use NavController or Compose.
     * @param activity The current activity.
     */
    fun handleActivityNavigation(activity: Activity)

    /**
     * Handles navigation for conventional fragment-based navigation.
     * @param destination The destination of the navigation event.
     */
    fun handleConventionalNavigation(destination: NavDestination)

    /**
     * Handles navigation for Jetpack Compose-based navigation.
     * @param destination The destination of the navigation event.
     */
    fun handleComposeNavigation(destination: NavDestination)
}
