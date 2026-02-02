package com.sample.lightsession;

import android.app.Application;

import com.lightsession.LightSession;
import com.lightsession.LightSessionConfig;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        LightSessionConfig config = new LightSessionConfig(
                "fd149df7c02e32cec9f2b735cd1d35cbf30f6027",
                true,
                LightSessionConfig.CaptureQuality.MEDIUM
        );

        LightSession.getInstance().init(this, config);
    }
}