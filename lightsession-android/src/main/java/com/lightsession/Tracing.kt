package com.lightsession

import android.os.Trace

/**
 * Names the SDK's work inside a system trace, so somebody profiling their own app can see it.
 *
 * ## Why a library should do this
 *
 * `LightSessionThreadFactory` already names the SDK's threads, for the reason given there: an SDK
 * that cannot be identified in a profile gets blamed for whatever is near it. Thread names only
 * cover work that runs on the SDK's own threads, and measurement says that is the small part.
 *
 * Traced on a Galaxy Tab A7 while recording a scrolling list: the process spent about 2.1 extra
 * seconds of CPU per twenty-second stretch, of which the SDK's named threads accounted for 30 ms.
 * Everything else was on the host app's main thread and on shared coroutine dispatchers — and in
 * the trace it was *unlabelled time*, indistinguishable from the app's own work. A host developer
 * looking at that trace would see their own `traversal` slices get slower and have no way to tell
 * why.
 *
 * These sections are what turn that into an answer.
 *
 * ## Cost when nobody is tracing
 *
 * [Trace.beginSection] checks a process-global tag word before doing anything, so a disabled
 * section is a JNI call and a comparison. That is affordable a handful of times per capture, which
 * is where these are placed. It is *not* affordable per view or per semantics node, so nothing
 * inside the scan traversal is traced — the traversal is traced as a whole instead.
 *
 * ## The rule these have to obey
 *
 * A section must begin and end on the same thread, so [traced] wraps a synchronous block only.
 * Anything that hands work to another thread — the surface capture, which finishes on the copy
 * thread — is traced on each side separately rather than across the hand-off.
 */
internal object Tracing {
    /** Every capture attempt, whichever path it then takes. Counts the schedule, not the work. */
    const val CAPTURE_ATTEMPT = "LightSession.captureAttempt"
    const val PLAN_MASKS = "LightSession.planMasks"
    const val CAPTURE_SOFTWARE = "LightSession.capture.software"
    const val ENCODE = "LightSession.encodeJpeg"
    const val SPOOL = "LightSession.spool"
    const val WIREFRAME = "LightSession.wireframe"
}

/**
 * Runs [block] inside a named trace section.
 *
 * `inline` so the lambda is not an allocation on a path that runs on every captured frame, and
 * `finally` so a throw still closes the section — an unbalanced section corrupts every slice above
 * it in the trace, which is a worse failure than the exception.
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
