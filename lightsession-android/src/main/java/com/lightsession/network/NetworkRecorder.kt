package com.lightsession.network

import com.lightsession.mapper.ScreenMapperIntegration
import com.lightsession.session.SessionDataManager

/**
 * Where a recorded request goes, and the one place that decides whether it is recorded at all.
 *
 * Split from [LightSessionInterceptor] so the interceptor stays a thing a customer installs and
 * this stays a thing the SDK wires up. It also means the gate is in one place: an interceptor
 * left in a release build with `captureNetwork = false` costs a field read per request and
 * nothing else.
 *
 * Held as a nullable field rather than reached through `LightSession.getInstance()` for the same
 * reason [com.lightsession.errors.ErrorCapture] does: a request can be in flight before `init`
 * has run and after `reset` has, and "no manager yet" has to be an early return rather than a
 * lazily-built one.
 */
internal object NetworkRecorder {

    @Volatile
    private var dataManager: SessionDataManager? = null

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var sampleRate: Double = NetworkSampling.NO_SAMPLING

    fun install(
        sessionDataManager: SessionDataManager,
        captureNetwork: Boolean,
        networkSampleRate: Double = NetworkSampling.NO_SAMPLING,
    ) {
        dataManager = sessionDataManager
        enabled = captureNetwork
        sampleRate = networkSampleRate
    }

    /**
     * Records one request. Never throws — the interceptor guards the call as well, and both
     * matter: this one keeps a bug here from reaching an app that installed the interceptor,
     * and that one keeps a bug in the guard itself from doing the same.
     */
    fun record(
        method: String,
        host: String,
        path: String,
        status: Int,
        durationMs: Long,
        requestBytes: Long,
        responseBytes: Long,
        errorClass: String,
    ) {
        if (!enabled) return
        val manager = dataManager ?: return

        // The sampling decision, before any of the work below. A request the sample does not want
        // costs a hash of the session id and nothing else — no screen lookup, no JSON, no queue.
        // That matters more here than anywhere else in the SDK: this runs on the app's own
        // network threads, once per request, and sampling exists to make that cheaper.
        //
        // `status = 0` means the request never got an answer, which is a failure and one of the
        // ones most worth keeping.
        val failed = status >= 400 || status == 0
        val weight = NetworkSampling.weightFor(manager.currentSessionId(), sampleRate, failed)
            ?: return

        val mapper = ScreenMapperIntegration.getInstance()
        manager.addApiCall(
            method = method,
            host = host,
            path = path,
            status = status,
            durationMs = durationMs,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
            errorClass = errorClass,
            weight = weight,
            // The same fallback the error path takes, for the same measured reason: a Compose
            // Activity is named a grace period after resume, and a request fired from `init` or
            // a splash lands inside exactly that window. The Activity's own name is where the
            // mapper's naming ends up for the common topology anyway.
            screen = mapper.getCurrentScreen() ?: mapper.currentActivity()?.javaClass?.simpleName,
            screenId = mapper.getCurrentScreenId(),
        )
    }
}
