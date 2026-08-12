package com.sample.lightsession

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightsession.LightSession

/**
 * Ways an app breaks, one button each.
 *
 * Exists for the same reason `LoadingScreenActivity` does: nothing in this repository could
 * produce the case under study. Every crash here is honest — a real uncaught exception on the
 * thread named on the button, ending in the system's crash dialog — because the thing being
 * verified is the whole path *through* a dying process: handler → synchronous spool → process
 * death → next launch's drain. A "simulated" crash would skip exactly the part that matters.
 *
 * Drive it without touching the screen:
 * ```
 * adb shell am start -n com.sample.lightsession/.ErrorPlaygroundActivity --es action crash
 * adb shell am start -n com.sample.lightsession/.ErrorPlaygroundActivity --es action crash-bg
 * adb shell am start -n com.sample.lightsession/.ErrorPlaygroundActivity --es action handled
 * ```
 * The extra fires the same code as the button, half a second after the screen settles — enough
 * for the mapper to know which screen the error belongs to, which is the attribution under test.
 */
class ErrorPlaygroundActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Playground() }

        when (intent?.getStringExtra("action")) {
            "crash" -> after { crashMain() }
            "crash-bg" -> after { crashBackground() }
            "handled" -> after { handled() }
        }
    }

    /** Late enough for navigation to have named this screen; the point is the attribution. */
    private fun after(block: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed(block, 500)
    }

    private fun crashMain() {
        throw IllegalStateException("playground: crash on the main thread")
    }

    private fun crashBackground() {
        Thread({
            throw IllegalStateException("playground: crash on a background thread")
        }, "playground-worker").start()
    }

    private fun handled() {
        try {
            throw java.io.IOException("playground: caught and reported")
        } catch (e: java.io.IOException) {
            LightSession.getInstance().captureException(
                RuntimeException("payment failed", e),
                mapOf("gateway" to "stripe", "attempt" to 3),
            )
        }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun Playground() {
        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("Error playground") }) }) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { crashMain() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Crash — main thread")
                    }
                    Button(onClick = { crashBackground() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Crash — background thread")
                    }
                    Button(onClick = { handled() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Handled — captureException")
                    }
                }
            }
        }
    }
}
