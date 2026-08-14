package com.sample.lightsession

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Several Compose screens in one Activity, with the integration **deliberately missing**.
 *
 * Note the absence: `rememberNavController()` with no `.withNavigationTracking()`. Every other screen
 * here shows the SDK working; this one shows what a consumer who skipped a line actually gets, which
 * is worth being able to look at on purpose rather than discovering in a support thread.
 *
 * Expected in the map: `alpha`, `beta` and `gamma`, named from the routes — the same three screens
 * an integrated Activity produces. `NavControllerDiscovery` finds the controller in the slot table
 * when the grace period ends with nothing registered, so the missing line costs the destinations of
 * the first three seconds rather than every destination of the session.
 *
 * This screen is kept precisely because that recovery has to keep working. It has been wrong twice
 * in opposite directions: first the shape reported *nothing at all* — and the advice was logged
 * once per process, only when no other Activity had integrated, so in a sample like this one it
 * never fired either — and then it reported one node named after the Activity, which is what a real
 * app's map looked like when five destinations arrived as `MainActivity`. Discovery is the third
 * answer, and this Activity is how it stays honest.
 */
class UntrackedComposeNavActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // No withNavigationTracking(). On purpose. See the class kdoc.
            val nav = rememberNavController()

            MaterialTheme {
                NavHost(navController = nav, startDestination = "alpha") {
                    composable("alpha") {
                        UntrackedScreen("alpha", onNext = { nav.navigate("beta") })
                    }
                    composable("beta") {
                        UntrackedScreen("beta", onNext = { nav.navigate("gamma") })
                    }
                    composable("gamma") {
                        UntrackedScreen("gamma", onNext = { nav.popBackStack("alpha", false) })
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun UntrackedScreen(name: String, onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Untracked: $name", fontSize = 26.sp)
        Text(
            "Three destinations, no withNavigationTracking(). All three should arrive as the one " +
                "Activity name, and logcat should carry the advice.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = onNext, modifier = Modifier.padding(top = 24.dp)) { Text("Next") }
    }
}
