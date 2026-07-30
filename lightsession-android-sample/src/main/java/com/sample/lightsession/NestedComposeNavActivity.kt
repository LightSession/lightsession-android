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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lightsession.mapper.withNavigationTracking

/**
 * A NavHost inside a NavHost, both handed over.
 *
 * The shape a bottom-navigation app has: an outer graph for the tabs and an inner graph inside one of
 * them. Worth a screen of its own because the failure is quiet — wrap the outer controller only, and
 * the inner destinations never appear. They do not look missing; they look like screens nobody opens,
 * which is a conclusion somebody could act on.
 *
 * Expected in the map: `outerHome` and `outerDetail` from the outer graph, and `innerFirst` and
 * `innerSecond` from the inner one, with edges between them in the order they were visited.
 *
 * The rule this exists to demonstrate: wrap **every** NavController the app creates, not just the one
 * at the top. Both `rememberNavController()` calls below carry `.withNavigationTracking()`.
 */
class NestedComposeNavActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val outer = rememberNavController().withNavigationTracking()

            MaterialTheme {
                NavHost(navController = outer, startDestination = "outerHome") {
                    composable("outerHome") {
                        NestedScreen(
                            title = "outerHome",
                            note = "The outer graph. Its controller is wrapped.",
                            onNext = { outer.navigate("outerDetail") },
                        )
                    }
                    composable("outerDetail") {
                        // A second controller, created inside a destination of the first. This is the
                        // one that goes missing when only the outer is wrapped.
                        val inner = rememberNavController().withNavigationTracking()
                        NavHost(navController = inner, startDestination = "innerFirst") {
                            composable("innerFirst") {
                                NestedScreen(
                                    title = "innerFirst",
                                    note = "Inner graph, nested inside outerDetail.",
                                    onNext = { inner.navigate("innerSecond") },
                                    onBack = { outer.popBackStack() },
                                )
                            }
                            composable("innerSecond") {
                                NestedScreen(
                                    title = "innerSecond",
                                    note = "Reached only through the inner controller.",
                                    onNext = { inner.popBackStack() },
                                    onBack = { outer.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NestedScreen(
    title: String,
    note: String,
    onNext: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 26.sp)
        Text(
            note,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = onNext, modifier = Modifier.padding(top = 24.dp)) { Text("Next") }
        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
                Text("Leave the inner graph")
            }
        }
    }
}
