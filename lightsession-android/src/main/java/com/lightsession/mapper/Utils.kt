package com.lightsession.mapper

import android.app.Activity
import android.util.Log
import androidx.navigation.NavDestination
import androidx.navigation.fragment.FragmentNavigator

class Utils {

    /**
     * Extracts the Fragment class name from a NavDestination using reflection.
     * Note: This uses a private field and might break in future Navigation library versions.
     * A more robust way is to use `destination.className` if available (from `FragmentNavigator.Destination`).
     *
     * @param destination The NavDestination to inspect
     * @return The Fragment class name, or empty string if extraction fails
     */
    fun getFragmentClassNameSafely(destination: NavDestination): String {
        return if (destination is FragmentNavigator.Destination) {
            destination.className.substringAfterLast('.')
        } else {
            // Fallback for non-fragment destinations or if you need a generic name
            Log.w("ScreenMapper", "Destination is not a FragmentNavigator.Destination. Type: ${destination.javaClass.simpleName}. Using label or display name.")
            // You might want to use destination.label or destination.displayName here if available and more suitable
            // For now, falling back to the destination's own class simple name as a generic identifier
            val explicitClassName = destination.javaClass.getDeclaredField("_className") // Your original reflection
            try {
                explicitClassName.isAccessible = true
                val fullClassName = explicitClassName.get(destination) as String
                fullClassName.substringAfterLast('.')
            } catch (e: Exception) {
                Log.w("ScreenMapper", "Could not get _className from NavDestination: ${destination.javaClass.simpleName}. Error: ${e.message}")
                destination.javaClass.simpleName // Or destination.route or another property
            }

        }
    }

    /**
     * Returns the simple class name of an Activity.
     *
     * @param activity The Activity instance
     * @return The simple class name (e.g., "MainActivity")
     */
    fun getActivityClassName(activity: Activity): String {
        return activity::class.java.simpleName
    }

    /**
     * Extracts and formats a screen name from a navigation route string.
     *
     * This function cleans a raw route, which may contain path separators (`/`) or
     * query parameters (`?`), to produce a standardized screen name.
     *
     * The processing logic is as follows:
     * 1. It removes any query parameters (the substring after the first `?`).
     * 2. If the cleaned route contains path separators (`/`), it then splits the route
     * into segments. The first letter of each of these segments is capitalized,
     * and they are joined back together using `/` as a separator.
     * 3. If the cleaned route does not contain any path separators (`/`), it is returned as is,
     * without any capitalization changes.
     *
     * Usage Examples:
     * - "profile/about?id=123" returns "Profile/About"
     * - "settings?theme=dark" returns "Settings"
     * - "home" returns "home"
     * - "user/{userId}/details" returns "User/{UserId}/Details"
     * - "About" returns "About"
     *
     * @param route The navigation route string (e.g., "profile/settings", "dashboard?filter=all").
     * @return A formatted string representing the screen name (e.g., "Profile/About", "home").
     */
    fun extractScreenNameFromRoute(route: String): String {
        val cleanRoute = when {
            route.contains("?") -> route.split("?")[0] // Remove query parameters first
            else -> route
        }

        // Only split and capitalize if there are path segments
        return if (cleanRoute.contains("/")) {
            cleanRoute.split("/")
                .joinToString("/") { it.replaceFirstChar { char -> char.uppercase() } }
        } else {
            cleanRoute // Return as is if no path segments, respecting original casing
        }
    }
}