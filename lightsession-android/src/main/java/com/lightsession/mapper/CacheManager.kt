package com.lightsession.mapper

import android.content.Context

class CacheManager(context: Context) {
    private val prefs = context.getSharedPreferences("screen_mapper_cache", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_SENT_SCREENS = "sent_screens"
        private const val KEY_SENT_FLOWS = "sent_flows"
        private const val KEY_CAPTURED_SCREENS = "captured_screens"
    }

    fun handleAppVersionCheck(currentVersion: String) {
        val savedVersion = prefs.getString(KEY_APP_VERSION, null)

        if (savedVersion == null) {
            // First run, just save the version
            updateAppVersion(currentVersion)
            return
        }

        if (savedVersion != currentVersion) {
            // Version has changed, clear all cache and update the version
            clearAllCache()
            updateAppVersion(currentVersion)
        }
    }

    private fun updateAppVersion(version: String) {
        prefs.edit().putString(KEY_APP_VERSION, version).apply()
    }

    fun isScreenSent(screenId: String): Boolean {
        val sentScreens = getSentScreens()
        return sentScreens.contains(screenId)
    }

    fun isScreenFullyCaptured(screenId: String): Boolean {
        val capturedScreens = getCapturedScreens()
        return capturedScreens.contains(screenId)
    }

    fun markScreenAsSent(screenId: String, fullyCaptured: Boolean) {
        val sentScreens = getSentScreens().toMutableSet()
        sentScreens.add(screenId)
        prefs.edit().putStringSet(KEY_SENT_SCREENS, sentScreens).apply()

        if (fullyCaptured) {
            val capturedScreens = getCapturedScreens().toMutableSet()
            capturedScreens.add(screenId)
            prefs.edit().putStringSet(KEY_CAPTURED_SCREENS, capturedScreens).apply()
        }
    }

    fun isFlowSent(flowKey: String): Boolean {
        val sentFlows = getSentFlows()
        return sentFlows.contains(flowKey)
    }

    fun markFlowAsSent(flowKey: String) {
        val sentFlows = getSentFlows().toMutableSet()
        sentFlows.add(flowKey)
        prefs.edit().putStringSet(KEY_SENT_FLOWS, sentFlows).apply()
    }

    private fun clearAllCache() {
        prefs.edit()
            .remove(KEY_SENT_SCREENS)
            .remove(KEY_SENT_FLOWS)
            .remove(KEY_CAPTURED_SCREENS)
            .apply()
    }

    private fun getSentScreens(): Set<String> {
        return prefs.getStringSet(KEY_SENT_SCREENS, emptySet()) ?: emptySet()
    }

    private fun getSentFlows(): Set<String> {
        return prefs.getStringSet(KEY_SENT_FLOWS, emptySet()) ?: emptySet()
    }

    private fun getCapturedScreens(): Set<String> {
        return prefs.getStringSet(KEY_CAPTURED_SCREENS, emptySet()) ?: emptySet()
    }
}
