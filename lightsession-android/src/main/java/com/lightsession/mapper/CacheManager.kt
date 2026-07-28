package com.lightsession.mapper

import android.content.Context

/**
 * What this install has already reported, so it is not reported again.
 *
 * ## Why the writes are synchronized
 *
 * Every mark is a read-modify-write: fetch the set, copy it, add to it, store it back.
 * Those run from coroutines — a screenshot upload finishing while a navigation is being
 * recorded is ordinary — and two of them interleaving lose one of the additions.
 *
 * Losing a "sent" mark is cheap: the screen is reported twice and the server dedupes.
 * Losing a "fully captured" mark is not, because it is the flag that stops the SDK
 * scheduling another screenshot of a screen it has already got — so a lost one means a
 * capture, an upload and a settle-wait repeated on every visit to that screen for the rest
 * of the install.
 *
 * The lock is per instance, and `ScreenMapperIntegration` holds one, so it covers every
 * write in the process. It does not cover two processes sharing one SharedPreferences file,
 * which Android does not support anyway.
 */
class CacheManager(context: Context) {
    private val prefs = context.getSharedPreferences("screen_mapper_cache", Context.MODE_PRIVATE)

    private val writeLock = Any()

    companion object {
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_SENT_SCREENS = "sent_screens"
        private const val KEY_SENT_FLOWS = "sent_flows"
        private const val KEY_CAPTURED_SCREENS = "captured_screens"
    }

    fun handleAppVersionCheck(currentVersion: String) = synchronized(writeLock) {
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

    fun markScreenAsSent(screenId: String, fullyCaptured: Boolean) = synchronized(writeLock) {
        // One edit, not two. Both sets used to be committed separately, so a process death
        // between them left a screen marked sent but not captured — which is the pair of
        // states that makes the SDK re-capture forever.
        val editor = prefs.edit()
        editor.putStringSet(KEY_SENT_SCREENS, getSentScreens() + screenId)
        if (fullyCaptured) {
            editor.putStringSet(KEY_CAPTURED_SCREENS, getCapturedScreens() + screenId)
        }
        editor.apply()
    }

    fun isFlowSent(flowKey: String): Boolean {
        val sentFlows = getSentFlows()
        return sentFlows.contains(flowKey)
    }

    fun markFlowAsSent(flowKey: String) = synchronized(writeLock) {
        prefs.edit().putStringSet(KEY_SENT_FLOWS, getSentFlows() + flowKey).apply()
    }

    private fun clearAllCache() {
        prefs.edit()
            .remove(KEY_SENT_SCREENS)
            .remove(KEY_SENT_FLOWS)
            .remove(KEY_CAPTURED_SCREENS)
            .apply()
    }

    // Read through `+` at every call site rather than mutated: `getStringSet` may return
    // the very set SharedPreferences is holding, and mutating that is undefined.
    private fun getSentScreens(): Set<String> =
        prefs.getStringSet(KEY_SENT_SCREENS, emptySet()) ?: emptySet()

    private fun getSentFlows(): Set<String> =
        prefs.getStringSet(KEY_SENT_FLOWS, emptySet()) ?: emptySet()

    private fun getCapturedScreens(): Set<String> =
        prefs.getStringSet(KEY_CAPTURED_SCREENS, emptySet()) ?: emptySet()
}
