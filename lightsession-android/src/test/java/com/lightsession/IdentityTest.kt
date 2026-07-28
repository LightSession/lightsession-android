package com.lightsession

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rules that decide whose session this is.
 *
 * Worth testing away from a device because the expensive mistakes here are all silent. An
 * anonymous id that changes when it should not orphans everything recorded under the old one.
 * An anonymous id that *stays* when it should not hands one person's history to the next
 * person on that phone. Neither throws; both are only visible much later, in a dashboard,
 * as somebody who apparently did something they did not.
 */
class IdentityTest {

    /**
     * A SharedPreferences that behaves like one.
     *
     * The unit-test SDK stub returns defaults for everything, so the real one cannot be used
     * to test persistence — every read would come back null and every test would pass by
     * accident, including the ones about surviving a restart.
     */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun getAll(): MutableMap<String, *> = values
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
    }

    private class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun apply() {
            removals.forEach { prefs.values.remove(it) }
            prefs.values.putAll(pending)
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun clear(): SharedPreferences.Editor {
            prefs.values.clear()
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }

    private lateinit var prefs: FakePrefs

    /** One preferences file shared by every `Identity` built here, as on a device. */
    private fun identity(): Identity = Identity(prefs)

    @Before
    fun setUp() {
        prefs = FakePrefs()
    }

    @Test
    fun `an install gets one anonymous id and keeps it`() {
        val first = identity().anonymousId
        // A second Identity is what the next launch builds.
        assertEquals("a restart must not orphan what came before it", first, identity().anonymousId)
    }

    @Test
    fun `nobody is identified until the app says so`() {
        val identity = identity()
        assertNull(identity.userId)
        assertEquals("anonymous", identity.userType)
        assertEquals(identity.anonymousId, identity.effectiveId)
    }

    @Test
    fun `identifying changes whose session it is without changing the device`() {
        val identity = identity()
        val device = identity.anonymousId

        assertTrue(identity.identify("user-42"))
        assertEquals("user-42", identity.userId)
        assertEquals("identified", identity.userType)
        assertEquals("user-42", identity.effectiveId)
        assertEquals("the device is still the same device", device, identity.anonymousId)
    }

    @Test
    fun `a signed-in person is still signed in next launch`() {
        identity().identify("user-42")
        assertEquals("user-42", identity().userId)
    }

    @Test
    fun `identifying the same person again is not a change`() {
        val identity = identity()
        assertTrue(identity.identify("user-42"))
        assertFalse("an app may call this on every screen", identity.identify("user-42"))
        assertFalse("whitespace is not a different person", identity.identify("  user-42  "))
    }

    @Test
    fun `a blank id is refused`() {
        val identity = identity()
        assertFalse(identity.identify(""))
        assertFalse(identity.identify("   "))
        assertNull("and leaves nobody identified", identity.userId)
    }

    @Test
    fun `reset mints a new anonymous id`() {
        // The one that matters. Reusing the id would tie this device to the person who just
        // signed out, so the next person to sign in here inherits their history and the two
        // become one person in the dashboard.
        val identity = identity()
        identity.identify("user-42")
        val before = identity.anonymousId

        identity.reset()

        assertNull(identity.userId)
        assertEquals("anonymous", identity.userType)
        assertNotEquals("the next person must not inherit this device", before, identity.anonymousId)
        assertEquals(identity.anonymousId, identity.effectiveId)
    }

    @Test
    fun `the new anonymous id survives the restart after a reset`() {
        val identity = identity()
        identity.reset()
        val after = identity.anonymousId
        assertEquals(after, identity().anonymousId)
        assertNull("and the person stays forgotten", identity().userId)
    }

    @Test
    fun `two people on one phone do not share a device id`() {
        val identity = identity()
        identity.identify("ana")
        val anaDevice = identity.anonymousId

        identity.reset()
        identity.identify("bruno")

        assertNotEquals(anaDevice, identity.anonymousId)
        assertEquals("bruno", identity.userId)
    }
}
