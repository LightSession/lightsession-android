package com.lightsession.bench

import android.app.Application
import com.lightsession.bench.probe.LeakProbe

/**
 * The one thing this Application does is *not* start the SDK.
 *
 * Every other app in this repo calls `LightSession.init` from `onCreate`, which is the right way to
 * integrate and the wrong way to measure: by the time anything could take a baseline, the threads
 * exist, the classes are loaded and the first capture may already have run. There would be nothing
 * left to compare against.
 *
 * Here `init` is a button. The reading taken immediately before it is the only honest zero, and the
 * difference across it is what integrating the library costs at startup.
 *
 * What that difference does *not* include is the cost of shipping the library — its dex, its share
 * of the oat file, its resources. Those are in the APK whether `init` is called or not, so no
 * in-process comparison can see them. That number comes from building two APKs and comparing them,
 * which is a different exercise from this one.
 */
class BenchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LeakProbe.install()
    }
}
