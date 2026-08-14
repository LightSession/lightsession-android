package com.lightsession.errors

import android.util.Log
import com.lightsession.session.SessionDataManager
import com.lightsession.mapper.ScreenMapperIntegration
import java.util.concurrent.atomic.AtomicBoolean
import com.lightsession.LightSession

/**
 * Captures errors — the uncaught kind by installing itself, the handled kind through
 * `LightSession.captureException`.
 *
 * ## What a crash costs, and where the event goes
 *
 * Nothing here talks to the network. On an uncaught exception the crumb is built and handed to
 * [SessionDataManager.addError] with `fatal = true`, which spools it to disk *synchronously on the
 * crashing thread* — one `writeText` and one atomic rename, the same write `onDestroy` already
 * trusts. The upload happens on the next launch, when `init` drains the spool as it always has.
 * Trying to upload during a crash would be theatre: every thread this SDK owns is a daemon, and
 * daemons do not outlive the handler's return.
 *
 * ## The discipline
 *
 * `LightSessionThreadFactory` already states the doctrine: an SDK swallowing its host's crashes is
 * worse than one that lets them through. So:
 *
 *  * The previous handler is **always** invoked, in a `finally`, with the **original** throwable —
 *    whatever happened in ours. On Android that handler is the system's, which writes the crash to
 *    logcat, shows the dialog and kills the process. Every crash reporter installed before this
 *    one keeps working; this SDK adds an observer, not an owner.
 *  * Everything of ours runs inside its own `try` — a bug in error capture must not become the
 *    crash the user sees.
 *  * [crashing] makes the capture one-shot. A second thread crashing while the first is being
 *    written — or our own path throwing into itself — skips straight to the previous handler
 *    rather than re-entering.
 *
 * ## Where "which screen" comes from
 *
 * [ScreenMapperIntegration.getCurrentScreen] and [getCurrentScreenId], read synchronously at
 * capture time. They are plain fields written by the main thread, so a crash on another thread
 * may read a name one navigation stale — accepted: the alternative is locking main-thread state
 * from a crash handler, and a stale screen name is still the right neighbourhood while a deadlock
 * on the way down is a lost crash.
 */
internal object ErrorCapture {

    private val installed = AtomicBoolean(false)

    /** One capture per process death. See the class doc. */
    private val crashing = AtomicBoolean(false)

    @Volatile
    private var dataManager: SessionDataManager? = null

    @Volatile
    private var appPackage: String = ""

    /**
     * Starts capturing. Idempotent; the second caller changes nothing.
     *
     * Reads the handler that is already installed *at this moment* and chains to it, which is why
     * ordering matters to hosts with another crash reporter: whoever installs last runs first.
     * This SDK is happy anywhere in that chain because it always forwards.
     */
    fun install(sessionDataManager: SessionDataManager, packageName: String) {
        dataManager = sessionDataManager
        appPackage = packageName
        if (!installed.compareAndSet(false, true)) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(previous, crashing) { thread, throwable ->
                capture(throwable, handled = false, thread = thread)
            },
        )
        Log.d("ErrorCapture", "uncaught exception handler installed" +
            (previous?.let { ", chaining to ${it.javaClass.name}" } ?: ""))
    }

    /**
     * The shared capture path — `captureException` and the crash handler differ only in
     * [handled] and in which thread they name.
     */
    fun capture(
        throwable: Throwable,
        handled: Boolean,
        thread: Thread,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        val manager = dataManager ?: return
        val details = ErrorCrumb.build(throwable, handled, thread, appPackage)
        val mapper = ScreenMapperIntegration.getInstance()
        // The mapper may not have named the screen yet: a Compose Activity is named a grace
        // period after resume, and a startup crash — the class of crash most worth having —
        // happens inside exactly that window. The Activity's own name is where the mapper's
        // naming lands for the common topology anyway, so falling back to it turns "screen
        // unknown" into the answer the map would eventually give. Verified live: a crash 500ms
        // into a fresh process carried no screen at all before this.
        val screen = mapper.getCurrentScreen()
            ?: mapper.currentActivity()?.javaClass?.simpleName
        manager.addError(
            details = details,
            screen = screen,
            screenId = mapper.getCurrentScreenId(),
            attributes = attributes,
            fatal = !handled,
        )
    }

}

/**
 * The handler itself, separate from the [ErrorCapture] singleton so the discipline is testable
 * on the JVM — the same reasoning as `ScreenSource.kt`: the invariants that matter most here are
 * exactly the ones that only ever run while the process is dying, which is the worst possible
 * place to discover them wrong. Reduced to a previous handler, a latch and a capture function,
 * every one of them can be walked by a plain JUnit test.
 *
 * The invariants, in order of importance:
 *
 *  1. [previous] is **always** invoked, with the **original** throwable — whatever [capture]
 *     did, including throwing. An SDK swallowing its host's crashes is worse than one that
 *     lets them through, and downstream is the system handler that writes logcat and kills
 *     the process, plus any crash reporter installed before this SDK.
 *  2. [capture] runs at most once per process. A second thread crashing while the first crash
 *     is still being written — or our own capture throwing into itself — must fall straight
 *     through to [previous], not re-enter.
 */
internal class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val once: AtomicBoolean,
    private val capture: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (once.compareAndSet(false, true)) {
                capture(thread, throwable)
            }
        } catch (t: Throwable) {
            // Never let error capture become the error. The log is all the record there is.
            Log.e("ErrorCapture", "failed to record a crash", t)
        } finally {
            // The original throwable, not anything of ours: the system handler's logcat entry
            // and every reporter behind us must see the crash the app actually had.
            previous?.uncaughtException(thread, throwable)
        }
    }
}
