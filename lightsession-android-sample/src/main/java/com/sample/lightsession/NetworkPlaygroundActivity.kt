package com.sample.lightsession

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.lightsession.network.LightSessionInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Real HTTP requests, one button each, so the network capture can be watched end to end.
 *
 * Exists for the reason `ErrorPlaygroundActivity` does: nothing else in this repository makes an
 * outbound request, so the whole path — interceptor → crumb → spool → ingest → typed columns —
 * had nothing to run against. And "real" is the point again. A fake recording would skip the
 * parts most likely to be wrong: that `contentLength()` does not consume the body the app is
 * about to read, that a failure arrives as the app's own exception, that a path is collapsed
 * before it leaves the device.
 *
 * The client is built the way a customer builds one — our interceptor added to *their* client,
 * which is the whole opt-in posture:
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(LightSessionInterceptor()).build()
 * ```
 *
 * Drive it without touching the screen:
 * ```
 * adb shell am start -n com.sample.lightsession/.NetworkPlaygroundActivity --es action ok
 * adb shell am start -n com.sample.lightsession/.NetworkPlaygroundActivity --es action notfound
 * adb shell am start -n com.sample.lightsession/.NetworkPlaygroundActivity --es action secret
 * adb shell am start -n com.sample.lightsession/.NetworkPlaygroundActivity --es action fail
 * adb shell am start -n com.sample.lightsession/.NetworkPlaygroundActivity --es action burst
 * ```
 */
class NetworkPlaygroundActivity : ComponentActivity() {

    /** 10.0.2.2 is the host machine from inside the emulator; 3002 is ls-api. */
    private val base = "http://10.0.2.2:3002"

    private val client = OkHttpClient.Builder()
        .addInterceptor(LightSessionInterceptor())
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Playground() }

        when (intent?.getStringExtra("action")) {
            "ok" -> after { get("$base/health") }
            "notfound" -> after { get("$base/api/v1/projects/84321/nope") }
            "secret" -> after { get("$base/api/v1/session?token=eyJhbGciOiJIUzI1NiJ9&page=2") }
            "fail" -> after { get("http://10.0.2.2:9/never") }
            "burst" -> after { repeat(6) { get("$base/health") }; get("$base/api/v1/projects/9/x") }
        }
    }

    /**
     * Half a second after the screen settles, like the error playground: long enough for the
     * mapper to have named the screen, which is the attribution under test — a request has to
     * arrive on the screen that made it.
     */
    private fun after(action: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({ Thread { action() }.start() }, 500)
    }

    /**
     * Reads the body on purpose. `contentLength()` in the interceptor must not have consumed the
     * stream — that is the classic way an interceptor breaks the app it is measuring, and it is
     * invisible unless somebody downstream actually reads.
     */
    private fun get(url: String) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string()
                Log.d("NetworkPlayground", "$url -> ${response.code}, ${body?.length ?: 0} chars")
            }
        } catch (failure: Exception) {
            Log.d("NetworkPlayground", "$url -> ${failure.javaClass.simpleName}")
        }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun Playground() {
        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("Network") }) }) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Every button makes a real request.", style = MaterialTheme.typography.bodyMedium)
                    Case("200 from ls-api") { get("$base/health") }
                    Case("404 with an id in the path") { get("$base/api/v1/projects/84321/nope") }
                    Case("a token in the query") {
                        get("$base/api/v1/session?token=eyJhbGciOiJIUzI1NiJ9&page=2")
                    }
                    Case("connection failure") { get("http://10.0.2.2:9/never") }
                    Case("six calls to one endpoint") { repeat(6) { get("$base/health") } }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Case(label: String, action: () -> Unit) {
        Button(onClick = { Thread { action() }.start() }, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
    }
}
