package com.sample.lightsession;

import android.app.Application;

import com.lightsession.LightSession;
import com.lightsession.LightSessionConfig;

public class App extends Application {

    // 10.0.2.2 is the host machine as seen from the Android emulator.
    // On a physical device, replace with your machine's LAN address.
    //
    // Ports match scripts/e2e.sh in the lightsession-rs repo: ingest runs on
    // 5055 because macOS ControlCenter (AirPlay Receiver) holds 5000.
    private static final String INGEST_URL = "http://10.0.2.2:5055";
    // 3002: ls-api. Was 3001, the Go backend, which no longer serves anything —
    // all of its routes are ported.
    private static final String API_URL = "http://10.0.2.2:3002";

    @Override
    public void onCreate() {
        super.onCreate();

        LightSessionConfig config = new LightSessionConfig(
                "dev-key",
                INGEST_URL,
                API_URL,
                true,
                LightSessionConfig.CaptureQuality.MEDIUM,
                1000L,   // captureIntervalMs — o valor realista de produção
                100L,    // interactionCaptureIntervalMs — rajada durante o gesto
                // sessionTimeoutMs must match LS_SESSION__IDLE_TIMEOUT_SECS on the
                // ingest service. 8s is what scripts/e2e.sh runs with; production
                // uses the 30s default on both sides.
                8_000L,
                6,               // flushAtFrameCount — low, to exercise the trigger
                256L * 1024,     // flushAtBytes
                4L * 1024 * 1024 // maxBufferedBytes
        );

        LightSession.getInstance().init(this, config);
    }
}
