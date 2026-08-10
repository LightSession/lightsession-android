package com.lightsession.bench.probe

import leakcanary.AppWatcher
import leakcanary.LeakCanary

/**
 * Whether objects the SDK was shown are still reachable after they should be gone.
 *
 * ## Why LeakCanary is kept on a leash
 *
 * Left at its defaults LeakCanary dumps the heap the moment it sees five retained objects, and it
 * does that **in this process**: hundreds of milliseconds stopped, tens of megabytes allocated for
 * the dump, then an analysis pass. Every one of those lands inside whatever arm happens to be
 * running, and the memory numbers become a measurement of LeakCanary.
 *
 * So dumping is off by default here and the yes/no question is answered a cheaper way. Registering
 * an object with [watch] and then reading [retainedCount] after a real GC says whether it survived —
 * which is the whole question — without dumping anything. Turning [dumpingEnabled] on is for the
 * follow-up question, *what* is holding it, and that one is worth a stopped process because by then
 * you are debugging rather than measuring.
 *
 * ## What is worth watching
 *
 * `Recorder` and `ScreenDrawing` are `internal`, so nothing here can hold one to watch it — which is
 * correct, and is also how a host app sees the library. What a host app *can* hand over is its
 * Activities, and those are what the SDK keys almost everything on: `originalCallbacks` maps them,
 * `currentActivityWeakRef`, `baseScreenOwner` and `modalRootView` point at them or into them, and
 * `registeredComposeControllers` pairs a NavController with the Activity that owns it. An Activity
 * that was recorded and then destroyed is therefore the single most informative object to watch,
 * and its decor View is the second — a View keeps the whole hierarchy and the Activity behind it.
 */
internal object LeakProbe {

    /**
     * Off while measuring, on while investigating. See the class comment: a heap dump inside a
     * measured arm is a large, sudden cost attributed to whatever was being measured.
     */
    var dumpingEnabled: Boolean = false
        set(value) {
            field = value
            LeakCanary.config = LeakCanary.config.copy(dumpHeap = value)
        }

    fun install() {
        // Explicit rather than relying on the default, because the default is on.
        dumpingEnabled = false
    }

    fun watch(target: Any, description: String) {
        AppWatcher.objectWatcher.expectWeaklyReachable(target, description)
    }

    /**
     * Watched objects that are still reachable.
     *
     * Read it *after* [MemProbe.forceGc], never before: LeakCanary only reclassifies an object as
     * retained once a collection has failed to take it, so a count read without a GC in between
     * reports everything recently watched as retained and means nothing.
     */
    val retainedCount: Int get() = AppWatcher.objectWatcher.retainedObjectCount

    fun clear() {
        AppWatcher.objectWatcher.clearWatchedObjects()
    }
}
