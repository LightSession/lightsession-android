package com.lightsession.replay

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import com.lightsession.LightSessionConfig
import com.lightsession.SessionDataManager
import com.lightsession.mapper.ScreenMapperIntegration

/**
 * Manages screen capture, batching of captured frames, and sending them to a remote server.
 */
class ReplayIntegration(
    private val context: Context,
    private val config: LightSessionConfig,
) {
    // Atomic because the capture callback now arrives on the encoder thread,
    // not the main thread — the JPEG compression moved off the UI thread.
    private val totalCaptures = AtomicInteger(0)
    private val uniqueCaptures = AtomicInteger(0)
    private val repeatedFrameSignals = AtomicInteger(0)
    private var sessionDataManager: SessionDataManager? = null
    private var recorder: Recorder? = null

    /**
     * Initializes the screen capture and automatic flush systems.
     * This method should be called once when the application starts.
     */
    fun init(sessionDataManager: SessionDataManager) {
        this.sessionDataManager = sessionDataManager
        start()
        Log.d("ReplayIntegration", "Initialized.")
    }

    private fun start() {
        val recorder = Recorder()
        this.recorder = recorder
        recorder.capture(
            context = context,
            idleDelayMillis = config.captureIntervalMs,
            burstDelayMillis = config.interactionCaptureIntervalMs,
            scaleFactor = getScaleFactor(config.captureQuality),
        ) { bitmapBytes ->
            handleCaptureResult(bitmapBytes)
        }
    }

    private fun getScaleFactor(quality: LightSessionConfig.CaptureQuality): Float {
        return when (quality) {
            LightSessionConfig.CaptureQuality.LOW -> ScreenDrawing.Companion.ScalePresets.LOW_QUALITY
            LightSessionConfig.CaptureQuality.MEDIUM -> ScreenDrawing.Companion.ScalePresets.MEDIUM_QUALITY
            LightSessionConfig.CaptureQuality.HIGH -> ScreenDrawing.Companion.ScalePresets.ORIGINAL
        }
    }

    private fun handleCaptureResult(bitmapBytes: ByteArray?) {
        totalCaptures.incrementAndGet()
        ReplayStats.recordDelivery()

        if (bitmapBytes != null) {
            val timestamp = System.currentTimeMillis()
            val isRepeatedFrame = bitmapBytes.contentEquals(Recorder.REPEATED_FRAME_SIGNAL)

            val currentScreen = try {
                ScreenMapperIntegration.getInstance().getCurrentScreen()
            } catch (e: Exception) {
                Log.e("ReplayIntegration", "Error getting current screen", e)
                null
            }

            if (isRepeatedFrame) {
                repeatedFrameSignals.incrementAndGet()
                ReplayStats.recordRepeated()
            } else {
                uniqueCaptures.incrementAndGet()
                ReplayStats.recordUnique()
            }

            sessionDataManager?.addFrame(
                imageData = bitmapBytes,
                isRepeatedFrame = isRepeatedFrame,
                currentScreen = currentScreen
            ) ?: run {
                Log.w("ReplayIntegration",
                    "SessionDataManager not available. Frame not sent. " +
                            "Total captures: $totalCaptures, Unique: $uniqueCaptures, Repeated: $repeatedFrameSignals")
            }
        }
    }

    /**
     * Forces a manual flush of the frame buffer.
     * This can be called from other parts of the application if needed.
     */
    fun forceFlush() {
        sessionDataManager?.forceFlush() ?: run {
            Log.w("ReplayIntegration", "Cannot force flush - SessionDataManager not available")
        }
    }

    /**
     * Called when the application is about to terminate.
     * Performs a final flush of any remaining frames.
     */
    fun onTerminate() {
        sessionDataManager?.forceFlush()
        recorder?.shutdown()
        recorder = null
        Log.d("ReplayIntegration",
            "terminated: ${totalCaptures.get()} captures, ${uniqueCaptures.get()} unique, " +
                    "${repeatedFrameSignals.get()} repeats")
    }

    /**
     * Called when the system is running low on memory.
     * Performs a flush to free up resources.
     */
    fun onLowMemory() {
        sessionDataManager?.forceFlush()
        Log.d("ReplayIntegration", "Low memory - forcing flush")
    }

    /**
     * Get current capture statistics
     */
    fun getStats(): Map<String, Int> {
        return mapOf(
            "totalCaptures" to totalCaptures.get(),
            "uniqueCaptures" to uniqueCaptures.get(),
            "repeatedFrameSignals" to repeatedFrameSignals.get()
        )
    }
}
