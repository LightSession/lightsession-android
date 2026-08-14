package com.lightsession.mapper

import android.content.Context
import androidx.core.content.edit

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
internal class CacheManager(context: Context) {
    private val prefs = context.getSharedPreferences("screen_mapper_cache", Context.MODE_PRIVATE)

    private val writeLock = Any()

    companion object {
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_SENT_SCREENS = "sent_screens"
        private const val KEY_SENT_FLOWS = "sent_flows"
        private const val KEY_CAPTURED_SCREENS = "captured_screens"
        private const val KEY_WIREFRAME_RECTS = "wireframe_rects"
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
        prefs.edit { putString(KEY_APP_VERSION, version) }
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
        prefs.edit {
            putStringSet(KEY_SENT_SCREENS, getSentScreens() + screenId)
            if (fullyCaptured) {
                putStringSet(KEY_CAPTURED_SCREENS, getCapturedScreens() + screenId)
            }
        }
    }

    /**
     * How many rectangles the richest wireframe ever sent for this screen carried. 0 = unknown.
     *
     * ## The ratchet
     *
     * "Sent" used to be the whole memory: a boolean, so whatever wireframe went out first was the
     * screen's picture for the life of the install. That is exactly wrong for the screens that
     * matter — a loading screen's first capture is a spinner in a shell (measured: 37 rectangles
     * against the 81 the loaded screen has), and if a touch killed the late-content watch on that
     * first visit, the spinner was permanent.
     *
     * Remembering the count turns "sent" into a bar to clear. A later capture is sent only when it
     * carries strictly more rectangles, and a successful send raises the bar. Strictly more is
     * what makes it converge: a screen whose data varies between visits does not ping-pong,
     * because equal-or-poorer captures are silence, and the count can only rise as many times as
     * there are new maxima. It also means an SDK upgrade whose scanner finds more of the screen
     * heals every stored wireframe by itself on the next visit — which retires the standing defect
     * that this cache is invalidated by *app* version while wireframe quality changes with *SDK*
     * version.
     *
     * A count and not a hash, deliberately. The question the map asks is "is this a more complete
     * picture", not "is this a different picture" — a hash resends on every data change forever.
     *
     * 0 for a screen sent before this existed, which makes any capture with a single rectangle
     * "richer": every legacy install resends each stale screen once, records the bar, and goes
     * quiet. That one send per screen is the healing, not a bug.
     */
    fun wireframeRects(screenId: String): Int =
        getWireframeRects()
            .firstOrNull { it.startsWith("$screenId|") }
            ?.substringAfterLast('|')
            ?.toIntOrNull() ?: 0

    /**
     * Raises the bar for [screenId] to [rects]; never lowers it.
     *
     * Monotonic under any completion order, which matters because sends race: the first capture's
     * send and a late-content upgrade can complete out of order, and letting the smaller count win
     * would schedule a pointless re-upgrade on the next visit.
     */
    fun recordWireframeRects(screenId: String, rects: Int) = synchronized(writeLock) {
        val set = getWireframeRects()
        val current = set.firstOrNull { it.startsWith("$screenId|") }
        val bar = current?.substringAfterLast('|')?.toIntOrNull() ?: 0
        if (rects <= bar) return
        prefs.edit {
            putStringSet(
                KEY_WIREFRAME_RECTS,
                set - setOfNotNull(current) + "$screenId|$rects",
            )
        }
    }

    fun isFlowSent(flowKey: String): Boolean {
        val sentFlows = getSentFlows()
        return sentFlows.contains(flowKey)
    }

    fun markFlowAsSent(flowKey: String) = synchronized(writeLock) {
        prefs.edit { putStringSet(KEY_SENT_FLOWS, getSentFlows() + flowKey) }
    }

    /** Wipes every cache set. Private in spirit — the one internal caller is the ratchet's test,
     *  which needs a clean install-scoped store and cannot get one any other way. */
    internal fun clearAllCache() {
        // `commit`, not `apply`. This runs on an app-version change, right before the first
        // screens of the new version are captured — if the wipe is still in flight when they are
        // recorded, the new bar races the old file and can lose. Blocking here costs a disk write
        // once per upgrade, which is nothing beside getting it wrong.
        prefs.edit(commit = true) {
            remove(KEY_SENT_SCREENS)
            remove(KEY_SENT_FLOWS)
            remove(KEY_CAPTURED_SCREENS)
            remove(KEY_WIREFRAME_RECTS)
        }
    }

    // Read through `+` at every call site rather than mutated: `getStringSet` may return
    // the very set SharedPreferences is holding, and mutating that is undefined.
    private fun getSentScreens(): Set<String> =
        prefs.getStringSet(KEY_SENT_SCREENS, emptySet()) ?: emptySet()

    private fun getSentFlows(): Set<String> =
        prefs.getStringSet(KEY_SENT_FLOWS, emptySet()) ?: emptySet()

    private fun getCapturedScreens(): Set<String> =
        prefs.getStringSet(KEY_CAPTURED_SCREENS, emptySet()) ?: emptySet()

    private fun getWireframeRects(): Set<String> =
        prefs.getStringSet(KEY_WIREFRAME_RECTS, emptySet()) ?: emptySet()
}
