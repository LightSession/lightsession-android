package com.lightsession.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An Activity that exists to be destroyed.
 *
 * The leak question the bench can actually ask is about Activities. `Recorder` and `ScreenDrawing`
 * are `internal`, so nothing outside the library can hold one — but an Activity is exactly what a
 * host app hands over, and it is what the SDK keys most of its state on: `originalCallbacks` maps
 * Activities, `currentActivityWeakRef` and `baseScreenOwner` point at one, `modalRootView` points
 * into one, and the window callback is swapped on one.
 *
 * So the hunt is: open this, let it be resumed and recorded, close it, collect, and ask whether it
 * is still reachable. It carries a little content because an empty Activity gives the mapper nothing
 * to read and would exercise a shorter path than a real screen does.
 */
class ScratchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("scratch screen", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Opened by the leak hunt, recorded, then finished. If the SDK is still " +
                            "holding it after a collection, LeakCanary has something to say.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
