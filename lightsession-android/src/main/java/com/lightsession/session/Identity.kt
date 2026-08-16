package com.lightsession.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.util.Log
import java.util.UUID

/**
 * Who the session belongs to.
 *
 * ## Why this exists as a type
 *
 * Identity was spread across two files that disagreed. `LightSession.initializeUserId` wrote a
 * UUID into `distinctId`, which nothing ever read; `SessionDataManager.generateAnonymousId`
 * wrote `anon_<8 hex>` to *the same preference key*, so whichever ran first decided the
 * format — and `LightSession` ran first, which made the second one's branch dead code that
 * looked alive. There was also a `userIdentifiedExplicitly` flag, never read, next to a
 * `userType` that was hardcoded to `"anonymous"` because nothing could ever set it.
 *
 * One owner, one key per fact.
 *
 * ## The two ids
 *
 * The **anonymous id** belongs to the install. It is minted once and it is what every session
 * is recorded under until somebody says who they are.
 *
 * The **user id** belongs to the person and comes from the app — its own identifier for them,
 * whatever their database calls it. It survives a restart, because a signed-in person is
 * still signed in tomorrow.
 *
 * Both travel: the user id as the session's identity, the anonymous id alongside it on an
 * `identify`, so the server can record that this device was this person. That mapping is the
 * point — a session recorded before sign-in is unattributable without it, and the part before
 * sign-in is the part worth watching.
 */
internal class Identity(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "LightSession.Identity"
        private const val PREFS = "LightSessionPrefs"

        /**
         * The key the old code used, kept as-is.
         *
         * Renaming it would mint a new anonymous id for everyone who upgrades, orphaning the
         * history already recorded under the old one — a migration with no upside.
         */
        private const val ANONYMOUS_ID = "anonymous_user_id"
        private const val USER_ID = "identified_user_id"

        /**
         * Takes preferences rather than a `Context`.
         *
         * What this class needs is two strings that survive a restart, and a `Context` is a
         * hundred other things — including one that cannot be stood up in a unit test without
         * implementing every abstract member of it. Persistence is exactly what has to be
         * tested here, so it had to be injectable.
         */
        fun from(context: Context): Identity =
            Identity(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    @Volatile
    var anonymousId: String = prefs.getString(ANONYMOUS_ID, null)?.takeIf { it.isNotBlank() }
        ?: mint()
        private set

    /** Null until the app calls `identify`. */
    @Volatile
    var userId: String? = prefs.getString(USER_ID, null)?.takeIf { it.isNotBlank() }
        private set

    /** The id everything is reported under: the person if known, the device if not. */
    val effectiveId: String get() = userId ?: anonymousId

    val userType: String get() = if (userId != null) "identified" else "anonymous"

    /**
     * Records who the person is. Returns false if there was nothing to change.
     *
     * Idempotent on purpose: an app that calls this on every screen — which is a reasonable
     * thing to do, since it is the cheapest way to be sure — should not produce an identify
     * per screen. Traits are not compared, so a call that only changes traits does report.
     */
    fun identify(userId: String): Boolean {
        val trimmed = userId.trim()
        if (trimmed.isEmpty()) {
            Log.w(TAG, "identify called with a blank id; ignored")
            return false
        }
        if (trimmed == this.userId) return false
        this.userId = trimmed
        prefs.edit { putString(USER_ID, trimmed) }
        Log.d(TAG, "identified")
        return true
    }

    /**
     * Forgets the person, and gives the device a new anonymous id.
     *
     * The new id is the part that matters. Keeping the old one would alias it to the person
     * who just signed out, so whoever signs in next on that device inherits their history and
     * the two become one person. That is the classic mistake in this feature, and the server's
     * primary key on `(project, anonymous_id)` is there to hold if this ever fails to.
     */
    fun reset() {
        userId = null
        anonymousId = mint()
        prefs.edit {
            remove(USER_ID)
            putString(ANONYMOUS_ID, anonymousId)
        }
        Log.d(TAG, "reset; new anonymous id minted")
    }

    private fun mint(): String = UUID.randomUUID().toString().also {
        prefs.edit { putString(ANONYMOUS_ID, it) }
    }
}
