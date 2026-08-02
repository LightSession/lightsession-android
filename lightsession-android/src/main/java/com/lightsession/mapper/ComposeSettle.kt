package com.lightsession.mapper

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.snapshots.Snapshot
import java.lang.ref.WeakReference

/**
 * Waits for a Compose screen to finish composing, instead of guessing how long that takes.
 *
 * # The problem
 *
 * For an Activity, `onActivityResumed` runs after measure and layout — the hierarchy is already
 * drawable and the skeleton can be generated on the spot.
 *
 * Compose does not work that way. The NavController's `OnDestinationChangedListener` fires when the
 * *destination* changes, which happens before the composition has emitted a single LayoutNode.
 * Capturing at that moment returns an empty tree, and it fails silently: the bitmap is valid, just
 * blank.
 *
 * The previous attempt was `postDelayed(1000)`, and `postDelayed(300)` on the other path. No number
 * works — it is too long for a static screen and too short for one fetching from the network, and
 * when it is wrong it stores a wrong skeleton rather than none.
 *
 * # The solution
 *
 * There is no "composition finished" callback, because conceptually it never finishes: it only stops
 * changing. So that is what gets watched:
 *
 * * [Snapshot.registerApplyObserver] fires on every state application — that is, on every
 *   recomposition that actually changed something;
 * * `OnDrawListener` fires on every draw;
 * * a [Choreographer] frame callback checks, each frame, whether both have been quiet for [quietMs].
 *
 * When the quiet lasts long enough **and** the composition has at least one node, the screen has
 * settled. A fast screen is captured in about 100ms rather than 1000ms; a slow one waits as long as
 * it needs, up to [timeoutMs].
 *
 * The snapshot observer only writes a timestamp, so the cost per recomposition is negligible —
 * nothing walks the composition every frame.
 */
internal class ComposeSettleDetector(
    private val quietMs: Long = DEFAULT_QUIET_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    companion object {
        /** How long both signals must stay quiet before the screen counts as settled. */
        const val DEFAULT_QUIET_MS = 120L

        /**
         * The absolute ceiling. A screen with an endless animation — a shimmer, a spinner — never
         * goes quiet, so past this point whatever is there is captured, which is better than having
         * no skeleton at all.
         */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }

    private var handle: Handle? = null

    /**
     * A wait in progress, and cancellable because the caller needs it to be: the person can navigate
     * again, or touch the screen — which by the ScreenMapper's own rule disqualifies it from being
     * mapped, since a screen someone has already changed is no longer the screen they arrived at.
     */
    inner class Handle internal constructor(
        private val activityRef: WeakReference<Activity>,
        private val hasContent: () -> Boolean,
        private val onSettled: (Activity) -> Unit,
    ) {
        private val startedAt = SystemClock.uptimeMillis()

        @Volatile
        private var lastChangeAt = SystemClock.uptimeMillis()

        @Volatile
        private var cancelled = false

        private var snapshotHandle: androidx.compose.runtime.snapshots.ObserverHandle? = null
        private var drawListener: ViewTreeObserver.OnDrawListener? = null
        private var frameCallback: Choreographer.FrameCallback? = null
        private var observedView: WeakReference<View>? = null

        internal fun start() {
            val activity = activityRef.get() ?: return finish(null)
            val root = activity.window?.decorView ?: return finish(null)

            // Any applied state is a recomposition that changed something.
            snapshotHandle = Snapshot.registerApplyObserver { _, _ ->
                lastChangeAt = SystemClock.uptimeMillis()
            }

            val listener = ViewTreeObserver.OnDrawListener {
                lastChangeAt = SystemClock.uptimeMillis()
            }
            runCatching { root.viewTreeObserver.addOnDrawListener(listener) }
                .onSuccess {
                    drawListener = listener
                    observedView = WeakReference(root)
                }

            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (cancelled) return
                    val now = SystemClock.uptimeMillis()

                    val current = activityRef.get()
                    if (current == null || current.isFinishing || current.isDestroyed) {
                        return finish(null)
                    }

                    val quietFor = now - lastChangeAt
                    val elapsed = now - startedAt

                    if (quietFor >= quietMs && hasContent()) {
                        Log.d("ComposeSettle", "settled after ${elapsed}ms (quiet ${quietFor}ms)")
                        return finish(current)
                    }

                    if (elapsed >= timeoutMs) {
                        // No content by the ceiling: capturing would store a blank skeleton, which
                        // is worse than storing none. Give up instead.
                        val content = hasContent()
                        Log.w(
                            "ComposeSettle",
                            "timed out after ${elapsed}ms (hasContent=$content)"
                        )
                        return finish(if (content) current else null)
                    }

                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            teardown()
        }

        private fun finish(activity: Activity?) {
            if (cancelled) return
            cancelled = true
            teardown()
            onSettled.let { callback ->
                if (activity != null) callback(activity)
            }
        }

        private fun teardown() {
            snapshotHandle?.dispose()
            snapshotHandle = null

            frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            frameCallback = null

            drawListener?.let { listener ->
                observedView?.get()?.let { view ->
                    runCatching { view.viewTreeObserver.removeOnDrawListener(listener) }
                }
            }
            drawListener = null
            observedView = null
        }
    }

    /**
     * Waits for the screen to settle, then calls [onSettled] on the main thread.
     *
     * `onSettled` is not called if the wait is cancelled, if the Activity goes away, or if the
     * timeout expires without the composition producing a single node.
     *
     * @param hasContent evaluated only once the screen looks quiet, not on every frame.
     */
    fun await(
        activity: Activity,
        hasContent: () -> Boolean,
        onSettled: (Activity) -> Unit,
    ): Handle {
        cancel()
        val handle = Handle(WeakReference(activity), hasContent, onSettled)
        this.handle = handle

        // Started on the main thread, whatever thread asked.
        //
        // Everything this touches is main-thread-only: `Choreographer.getInstance()` throws
        // "The current thread must have a looper!" outright, `ViewTreeObserver` is not synchronised,
        // and reading a view hierarchy off the main thread is undefined at best. That used to be
        // satisfied by accident — only Compose hosts came through here, and they are always reported
        // from the main thread. When every capture path started using this, one that runs on a
        // background thread began throwing, and the screen lost its wireframe silently: the exception
        // was caught upstream and the real screenshot arrived five seconds later, covering the hole.
        //
        // Asserting the requirement here rather than at each caller, because it is this class's
        // requirement and a caller cannot be expected to know it.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handle.start()
        } else {
            Handler(Looper.getMainLooper()).post {
                // The next `await` may have cancelled this one while the post was in flight.
                if (this.handle === handle) handle.start()
            }
        }
        return handle
    }

    fun cancel() {
        handle?.cancel()
        handle = null
    }
}
