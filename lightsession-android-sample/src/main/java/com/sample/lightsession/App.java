package com.sample.lightsession;

import android.app.Application;

import com.lightsession.LightSession;
import com.lightsession.LightSessionConfig;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        LightSessionConfig config = new LightSessionConfig(
                "1ef80924561895395e71b7f8dbe3eba7d1d5fc43",
                false,
                LightSessionConfig.CaptureQuality.MEDIUM
        );

        LightSession.getInstance().init(this, config);
    }
}