package com.lightsession.bench.run

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.lightsession.bench.ScratchActivity
import com.lightsession.bench.probe.LeakProbe
import com.lightsession.bench.probe.MemProbe
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the hunt found. [stillReachable] is the answer; the rest is how it was reached. */
internal data class LeakResult(
    val watched: Int,
    val stillReachable: Int,
    val note: String,
)

/**
 * Opens a screen, lets the SDK record it, closes it, and asks whether the SDK let go.
 *
 * ## Why an Activity, and not the recorder
 *
 * `Recorder` and `ScreenDrawing` are `internal`, so no code outside the library can hold one to
 * watch — which is not an obstacle so much as the honest boundary: it is also all a host app can
 * see. What a host app *does* hand over is Activities, and they are what the SDK keys most of its
 * state on. `originalCallbacks` maps them, `currentActivityWeakRef` and `baseScreenOwner` point at
 * one, `modalRootView` points into one, `registeredComposeControllers` pairs a NavController with
 * the Activity that owns it, and the window callback is swapped on one. An Activity that was
 * recorded and then destroyed passes through every one of those.
 *
 * The decor View is watched too. A View reaches its whole hierarchy and, through the context, the
 * Activity behind it — so a `View` held somewhere is a leak an Activity-only watch can still miss
 * when the Activity reference itself was correctly dropped.
 *
 * ## Why the answer is a count and not a heap dump
 *
 * Dumping costs hundreds of milliseconds and tens of megabytes *in this process*, which is why
 * [LeakProbe] keeps it off. Registering the object and reading the retained count after a real
 * collection answers "did it survive" for almost nothing. Turn dumping on afterwards if the answer
 * is yes and the next question is who is holding it.
 */
internal class LeakHunt(
    private val app: Application,
    private val listener: (String) -> Unit,
) {

    suspend fun run(): LeakResult = withContext(Dispatchers.Default) {
        LeakProbe.clear()

        var ref: WeakReference<Activity>? = null
        var decorRef: WeakReference<Any>? = null
        var destroyed = false

        val watcher = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is ScratchActivity) ref = WeakReference(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is ScratchActivity) destroyed = true
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
        }

        app.registerActivityLifecycleCallbacks(watcher)
        try {
            listener("opening the scratch screen")
            withContext(Dispatchers.Main) {
                app.startActivity(
                    Intent(app, ScratchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            // Long enough for the mapper to name the screen and for at least one capture at the
            // default one-second interval. Closing it sooner would test an Activity the SDK never
            // finished looking at, which is not the case anyone worries about.
            delay(3_500)

            val activity = ref?.get()
            if (activity == null) {
                return@withContext LeakResult(0, 0, "the scratch screen never reached onCreate")
            }
            val decor = activity.window?.decorView

            listener("closing it")
            withContext(Dispatchers.Main) { activity.finish() }

            var waited = 0
            while (!destroyed && waited < 5_000) {
                delay(100)
                waited += 100
            }
            if (!destroyed) {
                return@withContext LeakResult(0, 0, "it never reached onDestroy; nothing to conclude")
            }

            LeakProbe.watch(activity, "ScratchActivity, finished")
            var watched = 1
            if (decor != null) {
                LeakProbe.watch(decor, "ScratchActivity decor view, finished")
                watched = 2
            }
            decorRef = decor?.let { WeakReference<Any>(it) }

            // The local references have to go before collecting, or this function is the leak.
            @Suppress("UNUSED_VALUE")
            ref = null

            listener("collecting")
            MemProbe().forceGc()
            // LeakCanary reclassifies on its own schedule after a collection; give it a moment
            // before reading, or the count is of objects it has not looked at yet.
            delay(1_500)

            val retained = LeakProbe.retainedCount
            LeakResult(
                watched = watched,
                stillReachable = retained,
                note = when {
                    retained == 0 -> "the Activity and its view tree were collected"
                    else -> "$retained of $watched still reachable — turn heap dumping on to see " +
                        "the path, then reproduce"
                },
            )
        } finally {
            app.unregisterActivityLifecycleCallbacks(watcher)
            // Referenced so the compiler keeps it alive to this point rather than collecting the
            // decor early and making the watch look successful for the wrong reason.
            decorRef?.get()
        }
    }
}
