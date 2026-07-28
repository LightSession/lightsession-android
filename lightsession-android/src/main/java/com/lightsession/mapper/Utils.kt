package com.lightsession.mapper

import android.app.Activity
import androidx.navigation.NavDestination
import androidx.navigation.fragment.DialogFragmentNavigator
import androidx.navigation.fragment.FragmentNavigator

class Utils {

    /**
     * A name for a destination in a fragment graph, from public API only.
     *
     * The previous version reached for a private field whenever the destination was not a
     * `FragmentNavigator.Destination`:
     *
     * ```
     * val explicitClassName = destination.javaClass.getDeclaredField("_className")
     * try { ... } catch (e: Exception) { ... }
     * ```
     *
     * The `getDeclaredField` call sits *outside* the try that was meant to protect it, so
     * `NoSuchFieldException` escaped — and this runs inside a
     * `NavController.OnDestinationChangedListener`, which had no handler either, so the
     * exception went up through `dispatchOnDestinationChanged` and took the host app down.
     * Not hypothetical: `ActivityNavigator.Destination` declares no `_className` (checked
     * against navigation-runtime 2.8.4 and 2.8.5), so any app with an `<activity>`
     * destination in a fragment graph crashed on navigating to it. A `NavGraph` or a
     * custom navigator's destination does the same.
     *
     * Reflection was never needed for it. `className` is public on both fragment
     * destination types, and everything else has a `route` or a `displayName`.
     *
     * ## Why not `label`
     *
     * `label` is the obvious-looking fallback and it is the wrong one. It exists to be
     * displayed, so apps set it from a string resource and it changes with the device
     * language — while a screen name here is an identity, keyed on by the server and
     * hashed into the on-device cache. Taking the label would file one screen under a
     * different name in every locale the app ships.
     */
    fun getFragmentClassNameSafely(destination: NavDestination): String = when {
        destination is FragmentNavigator.Destination ->
            destination.className.substringAfterLast('.')

        // A `<dialog>` destination. Its own class, not that of whatever is behind it.
        destination is DialogFragmentNavigator.Destination ->
            destination.className.substringAfterLast('.')

        // Declared in the graph, so identical across builds and languages — which is what
        // an identity has to be. Arguments are dropped; they are per-visit, not per-screen.
        //
        // Only a null check is needed. `NavDestination.setRoute` rejects a blank route with
        // "Cannot have an empty route", so a route that exists is never blank — a guard
        // against that would be a branch no test could reach.
        destination.route != null ->
            destination.route!!.substringBefore('?').substringAfterLast('.')

        // The id as a resource name, or its hex value when the id has no name. Last
        // resort, and still stable within a build.
        else -> destination.displayName
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