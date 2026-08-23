package com.sample.lightsession

import android.app.Application
import com.lightsession.LightSession
import com.lightsession.LightSessionConfig

/**
 * The sample's integration, which is the whole point of the sample.
 *
 * Kotlin and named arguments, deliberately. This was Java passing eleven positional arguments to a
 * constructor that now takes twenty-one, and every field added to the config since moved every
 * argument after it onto a different parameter — it ended up handing a millisecond count to
 * `wireframeMode` and would not compile. Named arguments cannot drift that way, and they also read
 * as documentation: a reader sees which knob each number turns without counting commas.
 *
 * Java callers are not locked out — the config constructor is `@JvmOverloads` — and the activities
 * beside this one are still Java, which keeps the SDK's public API honest about being usable from
 * it. This one file being Kotlin is about the config, not about the language.
 *
 * Only values that differ from the default are passed. Restating a default teaches nothing and goes
 * stale silently the day the default changes.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        LightSession.getInstance().init(
            this,
            LightSessionConfig(
                apiKey = "dev-key",
                // 10.0.2.2 is the host machine as seen from the Android emulator. On a physical
                // device this has to be the machine's LAN address instead.
                //
                // Ingest is on 5055 rather than 5000 because macOS ControlCenter — AirPlay
                // Receiver — holds 5000. Matches scripts/e2e.sh in the lightsession-rs repo.
                ingestUrl = "http://10.0.2.2:5055",
                // 3002 is ls-api. It was 3001, the Go backend, which no longer serves anything.
                apiUrl = "http://10.0.2.2:3002",
                // Sharper than the LOW default, because this app exists to be looked at.
                captureQuality = LightSessionConfig.CaptureQuality.MEDIUM,
                // Off by default in the SDK, because nothing else it does can break the app
                // it watches. On here because NetworkPlaygroundActivity exists to exercise it.
                captureNetwork = true,
                // Read from prefs rather than hardcoded, so a script can vary it without a
                // rebuild:
                //
                //   adb shell am start -n com.sample.lightsession/.NetworkPlaygroundActivity \
                //       --ef rate 0.1
                //   adb shell am force-stop com.sample.lightsession   # then relaunch
                //
                // Two launches, because the rate is read here — in `onCreate` of the
                // Application — and the first launch is what writes it. The alternative is a
                // rebuild per rate, which is slower and easy to forget.
                networkSampleRate = networkSampleRate(),
                // Must match LS_SESSION__IDLE_TIMEOUT_SECS on the ingest service. 8s is what
                // scripts/e2e.sh runs with; production uses the 30s default on both sides. Shorter
                // splits sessions the server would keep whole, longer keeps sending to one it has
                // already closed.
                sessionTimeoutMs = 8_000L,
                // Low on purpose, so a short poke at the sample exercises the flush triggers
                // instead of buffering until the app is closed.
                flushAtFrameCount = 6,
                flushAtBytes = 256L * 1024,
                maxBufferedBytes = 4L * 1024 * 1024,
            ),
        )
    }

    /**
     * The sampling rate a previous launch asked for, or 1.0.
     *
     * A `Float` in prefs because that is what `am start --ef` writes, widened here; the config
     * takes a `Double` and the SDK's arithmetic is in doubles.
     */
    private fun networkSampleRate(): Double =
        getSharedPreferences("demo", MODE_PRIVATE)
            .getFloat("networkSampleRate", 1.0f)
            .toDouble()
}
