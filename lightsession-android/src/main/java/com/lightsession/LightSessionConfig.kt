package com.lightsession

data class LightSessionConfig(
    val apiKey: String,
    val enableReplay: Boolean = true,
    val captureQuality: CaptureQuality = CaptureQuality.LOW,
) {
    enum class CaptureQuality {
        LOW, MEDIUM, HIGH
    }
}
