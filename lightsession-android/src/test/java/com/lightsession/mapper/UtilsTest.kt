package com.lightsession.mapper

import androidx.navigation.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That naming a destination cannot take the host app down with it.
 *
 * The regression this guards is not a wrong name, it is a crash. The previous
 * implementation called `javaClass.getDeclaredField("_className")` for any destination
 * that was not a `FragmentNavigator.Destination`, with the call sitting outside the try
 * meant to protect it — so `NoSuchFieldException` escaped into
 * `NavController.dispatchOnDestinationChanged`, which has no handler, and killed the
 * process. `ActivityNavigator.Destination` declares no such field, so an `<activity>`
 * destination in a fragment graph was enough to trigger it.
 *
 * A plain `NavDestination` stands in for that whole family — activity destinations, nav
 * graphs, custom navigators — because what they have in common is precisely the thing the
 * old code assumed they had and they do not.
 */
class UtilsTest {

    private val utils = Utils()

    @Test
    fun `a destination with no class name does not throw`() {
        val destination = NavDestination("activity")
        // The assertion is that this line returns at all.
        val name = utils.getFragmentClassNameSafely(destination)
        assertEquals(destination.displayName, name)
    }

    @Test
    fun `a route names the destination when there is no class`() {
        val destination = NavDestination("composable").apply { route = "checkout/summary" }
        assertEquals("checkout/summary", utils.getFragmentClassNameSafely(destination))
    }

    @Test
    fun `route arguments are not part of the name`() {
        // Arguments are per-visit. Keeping them would file one screen under a new name for
        // every set of values it was opened with.
        val destination = NavDestination("composable").apply { route = "doctor/detail?id=7" }
        assertEquals("doctor/detail", utils.getFragmentClassNameSafely(destination))
    }

    @Test
    fun `navigation itself rejects a route that is not a name`() {
        // Why the route branch needs no blank check: the framework will not hold one. Worth
        // pinning rather than trusting, since the alternative is a defensive branch that no
        // test can reach and nobody can delete with confidence.
        for (bad in listOf("", "   ")) {
            val failure = runCatching { NavDestination("composable").route = bad }.exceptionOrNull()
            assertTrue("expected a rejection for '${'$'}bad'", failure is IllegalArgumentException)
        }
    }

    // ------------------------------------------------ route to screen name

    @Test
    fun `a route becomes one consistently cased name`() {
        // The old rule capitalised each segment but only when the route had a `/`, so a
        // single-segment route kept the app's own casing and a multi-segment one was
        // retitled. That is why one map held `login` and `doctors` beside `Home/Manager`.
        assertEquals("home", utils.extractScreenNameFromRoute("home"))
        assertEquals("home/manager", utils.extractScreenNameFromRoute("home/manager"))
        assertEquals("home/manager", utils.extractScreenNameFromRoute("Home/Manager"))
        assertEquals("about", utils.extractScreenNameFromRoute("About"))
    }

    @Test
    fun `arguments are not part of a screen name`() {
        assertEquals("profile/about", utils.extractScreenNameFromRoute("profile/about?id=123"))
        assertEquals("settings", utils.extractScreenNameFromRoute("settings?theme=dark"))
    }

    @Test
    fun `a route parameter keeps the name the app declared`() {
        // `{userId}` used to come out as `{UserId}`, which is not a name that appears
        // anywhere in the app.
        assertEquals(
            "user/{userid}/details",
            utils.extractScreenNameFromRoute("user/{userId}/details"),
        )
    }

    @Test
    fun `two routes differing only in case are one screen`() {
        // The point of the rule. Before, these were two rows on the server.
        assertEquals(
            utils.extractScreenNameFromRoute("Doctor/Detail/{id}"),
            utils.extractScreenNameFromRoute("doctor/detail/{id}"),
        )
    }
}
