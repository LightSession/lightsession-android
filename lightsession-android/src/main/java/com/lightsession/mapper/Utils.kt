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
     * A screen name from a navigation route.
     *
     * Arguments go — they identify a visit, not a screen, so keeping them would file one
     * screen under a new name for every set of values it was opened with. What is left is
     * lower-cased, and that is the part that changed.
     *
     * ## Why the casing was wrong
     *
     * It used to capitalise each segment, but only when the route contained a `/`:
     *
     * ```
     * "home"            -> "home"           // returned untouched
     * "home/manager"    -> "Home/Manager"   // capitalised
     * ```
     *
     * So a single-segment route kept whatever the app wrote and a multi-segment one was
     * retitled, and the same map ended up holding `login`, `doctors` and `splash` beside
     * `Home/Manager` and `Doctor/Detail/{id}`. It also capitalised inside route parameters,
     * turning `{userId}` into `{UserId}` — a name the app never declares.
     *
     * One rule now, applied to every route: lower case. Lower rather than capitalised
     * because it is what the app itself writes — routes are declared in lower case by
     * convention — so the name in the map matches the name in the source.
     *
     * This renames screens. A screen already reported as `Home/Manager` will be reported as
     * `home/manager` and the server, which keys on the name, will treat it as a new one and
     * keep both. That is a one-off cleanup, not a migration: the old rows stop receiving
     * data and can be deleted.
     */
    fun extractScreenNameFromRoute(route: String): String =
        route.substringBefore('?').lowercase()
}
