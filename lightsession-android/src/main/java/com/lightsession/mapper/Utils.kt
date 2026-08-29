package com.lightsession.mapper

import android.app.Activity
import androidx.navigation.NavDestination
import androidx.navigation.fragment.DialogFragmentNavigator
import androidx.navigation.fragment.FragmentNavigator

internal class Utils {

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
        //
        // `displayName` is `@RestrictTo(LIBRARY_GROUP)`, so lint objects and it is right to:
        // androidx can change or remove it in a minor release without it being a breaking
        // change for anyone but us. Suppressed here rather than disabled in the lint config,
        // so that the next call into a restricted API still fails the build.
        //
        // Kept rather than replaced because the alternatives are worse than the risk. What it
        // returns is the id's resource name, which needs `Resources` — a parameter this has no
        // reason to take otherwise — and the obvious substitutes change the answer: a bare id
        // or its hex is not the same string, and a screen name is an *identity*, keyed on by
        // the server and hashed into the on-device cache. Renaming screens to avoid an
        // annotation would split every affected screen into two rows.
        //
        // If it does go: reproduce it as
        // `runCatching { resources.getResourceName(id) }.getOrElse { "0x" + id.toHexString() }`,
        // which is what it does, and thread the `Resources` in.
        else -> @Suppress("RestrictedApi") destination.displayName
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
     * Query arguments go — they identify a visit, not a screen, so keeping them would file one
     * screen under a new name for every set of values it was opened with. What remains is named
     * by one principle: **the name in the map matches the name in the source**, because the map
     * is read by the person who wrote the source. The principle has produced two rules, because
     * routes are written two ways.
     *
     * ## Hand-written routes: lower case
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
     * turning `{userId}` into `{UserId}` — a name the app never declares. String routes are
     * declared in lower case by convention, so lower case is what matches the source.
     *
     * ## Type-safe routes: the class's own name
     *
     * The lower-case rule assumed the app wrote the route, and type-safe navigation broke the
     * assumption in the first real app integrated: there the pattern is generated from the
     * `@Serializable` destination's serial name, which is the fully qualified class name. Five
     * destinations arrived as
     * `com.example.app.ui.navigation.destination.orderdetail/{orderid}` — a package path
     * nobody declared, with the lower-casing destroying the one thing that separated the
     * words. What the app's author actually wrote is `OrderDetail(val orderId: Int)`, so that
     * is the name: the segment after the last dot, case kept, and the argument placeholders'
     * case kept with it.
     *
     * A dot in the route head is what selects the rule. Dots cannot appear in a class name's
     * place by accident, and a hand-written route with dots (`"settings.profile"`) loses its
     * prefix here — accepted, because the app that writes one is rarer than the app the other
     * reading breaks: every type-safe app gets package paths as screen names.
     *
     * Both rules rename screens on upgrade for the apps they touch. The server keys on the
     * name, so the old rows simply stop receiving data and can be deleted — a one-off cleanup,
     * not a migration.
     */
    fun extractScreenNameFromRoute(route: String): String {
        val pattern = route.substringBefore('?')
        val head = pattern.substringBefore('/')
        if ('.' !in head) return pattern.lowercase()
        return head.substringAfterLast('.') + pattern.removePrefix(head)
    }
}
