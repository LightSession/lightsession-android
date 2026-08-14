package com.sample.lightsession

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay

/**
 * The [UntrackedComposeNavActivity] shape with the `NavHost` arriving **after** the grace period.
 *
 * This is how the first real app integrated actually opens: the start destination hangs off an
 * auth check, so the first composition holds a loading indicator and no `NavHost` at all — the
 * controller does not exist yet. `rememberNavController()` runs only when the answer lands. Here
 * the answer takes [START_DESTINATION_DELAY_MS], deliberately past the grace period, which is the
 * case the one-shot discovery gets wrong by construction: it scans a composition that honestly
 * contains no controller, names the Activity, and never looks again. Whether a real install hits
 * this is decided by its network, which is the worst kind of bug to chase.
 *
 * Expected in the map: this Activity's name for the loading stretch — a screen the user genuinely
 * stared at — and then `alpha`/`beta`/`gamma` from the moment the NavHost composes, because the
 * state write that mounts it wakes `NavControllerWatch`, whose scan finds the controller within
 * a tick. Logcat carries the order of events: the grace-period advice first, then
 * "NavController(s) appeared ... without being handed over".
 */
class AsyncStartNavActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // No withNavigationTracking(), and no NavHost either — yet. On purpose.
            var startDestination by remember { mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                delay(START_DESTINATION_DELAY_MS)
                startDestination = "alpha"
            }

            MaterialTheme {
                when (val start = startDestination) {
                    null -> Loading()
                    else -> {
                        val nav = rememberNavController()
                        NavHost(navController = nav, startDestination = start) {
                            composable("alpha") {
                                AsyncScreen("alpha", onNext = { nav.navigate("beta") })
                            }
                            composable("beta") {
                                AsyncScreen("beta", onNext = { nav.navigate("gamma") })
                            }
                            composable("gamma") {
                                AsyncScreen("gamma", onNext = { nav.popBackStack("alpha", false) })
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Past COMPOSE_INTEGRATION_GRACE_MS by enough that a slow emulator frame cannot blur which path found the controller. */
        const val START_DESTINATION_DELAY_MS = 5_000L
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            "Deciding the start destination, slower than the grace period...",
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun AsyncScreen(name: String, onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Async start: $name", fontSize = 26.sp)
        Text(
            "The NavHost composed 5s in, after the grace period already fell back to the " +
                "Activity name. The watch should still have found the controller.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = onNext, modifier = Modifier.padding(top = 24.dp)) { Text("Next") }
    }
}
