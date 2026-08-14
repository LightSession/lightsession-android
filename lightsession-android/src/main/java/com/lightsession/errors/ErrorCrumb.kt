package com.lightsession.errors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.lightsession.LightSession
import com.lightsession.session.Identity
import com.lightsession.session.SessionDataManager

/**
 * A `Throwable`, flattened into the JSON an error breadcrumb carries.
 *
 * Pure — no Android types, no clock, no singletons — because the two places it runs could not be
 * more different: `LightSession.captureException` on an ordinary thread, and the uncaught-exception
 * handler on a thread the process is about to die on. Everything here is bounded CPU over objects
 * already in memory, which is the entire budget a crash path has.
 *
 * ## The shape
 *
 * ```json
 * {
 *   "handled": false,
 *   "thread": "main", "thread_id": 2,
 *   "exceptions": [
 *     { "type": "java.lang.IllegalStateException", "message": "...",
 *       "frames": [ {"class": "com.app.Foo", "method": "bar",
 *                    "file": "Foo.kt", "line": 42, "in_app": true} ] }
 *   ]
 * }
 * ```
 *
 * `exceptions` is the cause chain, **outermost first** — index 0 is the throwable that reached the
 * handler, the last entry is the root cause. The reader wants the headline before the history, and
 * a renderer that prefers the root cause can take the last element; order is a decision made once,
 * here, so it has to be stated.
 *
 * ## The bounds, and why each exists
 *
 *  * [MAX_CAUSES] — `getCause()` cycles exist in the wild (a cause that points back at its
 *    wrapper), and an unbounded walk inside a crash handler hangs the process on its way down.
 *    An identity set breaks the cycle; the depth cap bounds the honest-but-absurd chain.
 *  * [MAX_FRAMES] — a `StackOverflowError` carries a stack trace thousands of frames deep, almost
 *    all of them the same three lines repeating. The top of the stack is where the throw happened;
 *    the bottom is `main()`. Keep the top.
 *  * [MAX_MESSAGE] — messages are built by string concatenation in `catch` blocks and have arrived
 *    carrying whole request bodies. Two kilobytes keeps every message a person would read.
 *
 * ## `in_app`
 *
 * A frame is `in_app` when its class sits under the host's package name. It is a heuristic — an
 * app whose code lives outside its `applicationId` will under-mark — but it is the honest signal
 * available without configuration, and it is the input server-side grouping weighs most. The
 * SDK's own frames are never `in_app`, which is exactly right: a crash *through* the SDK groups
 * by where the app entered it.
 */
internal object ErrorCrumb {

    /** Chain links kept. Past this the chain is repetition, or a cycle the set already broke. */
    const val MAX_CAUSES = 8

    /** Frames kept per link, from the top — the throw site, not the thousand loops below it. */
    const val MAX_FRAMES = 120

    /** Characters of one message. */
    const val MAX_MESSAGE = 2_048

    /**
     * The error-specific half of the breadcrumb.
     *
     * The caller (`SessionDataManager.addError`) wraps this with the fields every crumb carries —
     * `type`, `timestamp`, `sequence`, identity, app version — and the screen fields, which are
     * read where the current screen is known. Keeping those out of here keeps this a function of
     * its arguments.
     */
    fun build(
        throwable: Throwable,
        handled: Boolean,
        thread: Thread,
        appPackage: String,
    ): JsonObject = buildJsonObject {
        put("handled", handled)
        put("thread", thread.name)
        put("thread_id", thread.id)
        put("exceptions", buildJsonArray {
            var current: Throwable? = throwable
            // Identity, not equals: two distinct exceptions can compare equal, and the cycle
            // being guarded against is literally the same object appearing twice.
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>(),
            )
            var depth = 0
            while (current != null && depth < MAX_CAUSES && seen.add(current)) {
                add(oneThrowable(current, appPackage))
                current = current.cause
                depth++
            }
        })
    }

    private fun oneThrowable(t: Throwable, appPackage: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(t.javaClass.name))
        t.message?.let { put("message", JsonPrimitive(it.take(MAX_MESSAGE))) }
        put("frames", buildJsonArray {
            // `stackTrace` clones the internal array; taking it once keeps clone count at one.
            val frames = t.stackTrace
            for (i in 0 until minOf(frames.size, MAX_FRAMES)) {
                val frame = frames[i]
                add(buildJsonObject {
                    put("class", JsonPrimitive(frame.className))
                    put("method", JsonPrimitive(frame.methodName))
                    // Null for classes compiled without debug info; -2 marks a native method.
                    // Both pass through as they are — inventing a value here would only make
                    // the server's picture cleaner than the truth.
                    frame.fileName?.let { put("file", JsonPrimitive(it)) }
                    put("line", JsonPrimitive(frame.lineNumber))
                    put("in_app", JsonPrimitive(isInApp(frame.className, appPackage)))
                })
            }
            if (frames.size > MAX_FRAMES) {
                // One marker frame, so a truncated trace says so instead of ending mid-air.
                add(buildJsonObject {
                    put("class", JsonPrimitive("…"))
                    put("method", JsonPrimitive("${frames.size - MAX_FRAMES} frames elided"))
                    put("in_app", JsonPrimitive(false))
                })
            }
        })
    }

    /**
     * Package-prefix match with a boundary check: `com.app` matches `com.app.Foo` but not
     * `com.appother.Foo`, which a bare `startsWith` would let through.
     */
    private fun isInApp(className: String, appPackage: String): Boolean {
        if (appPackage.isEmpty() || !className.startsWith(appPackage)) return false
        return className.length == appPackage.length || className[appPackage.length] == '.'
    }
}
