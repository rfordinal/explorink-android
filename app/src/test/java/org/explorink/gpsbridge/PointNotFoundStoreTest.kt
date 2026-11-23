package org.explorink.gpsbridge

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-404 rule (decision 3), against an in-memory stand-in for
 * `SharedPreferences` -- the real class returns nothing but defaults under
 * this module's `isReturnDefaultValues` unit-test setting, so a fake with real
 * storage is what makes "does a value actually persist" checkable at all.
 */
class PointNotFoundStoreTest {

    /** A `SharedPreferences` backed by a plain map -- storage, not a mock. */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Boolean>()

        override fun getBoolean(key: String?, defValue: Boolean) = values[key] ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Boolean?>()  // null means "remove"

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                pending[key!!] = null
                return this
            }

            override fun apply() {
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            // Nothing here reads or writes anything but booleans.
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
        }

        // Unused by PointNotFoundStore.
        override fun getAll() = values as Map<String, *>
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    @Test
    fun `the first 404 is remembered, not acted on`() {
        val store = PointNotFoundStore(FakePrefs())
        assertFalse(store.recordNotFound(562, 354))
    }

    @Test
    fun `the second 404 for the same shard fires`() {
        val store = PointNotFoundStore(FakePrefs())
        assertFalse(store.recordNotFound(562, 354))
        assertTrue(store.recordNotFound(562, 354))
    }

    @Test
    fun `a third 404 after the second is a fresh first one`() {
        val store = PointNotFoundStore(FakePrefs())
        assertFalse(store.recordNotFound(562, 354))
        assertTrue(store.recordNotFound(562, 354))
        // The streak was spent by the gone above -- this is a new one.
        assertFalse(store.recordNotFound(562, 354))
    }

    @Test
    fun `a successful read clears the streak`() {
        val store = PointNotFoundStore(FakePrefs())
        assertFalse(store.recordNotFound(562, 354))
        store.recordFound(562, 354)
        // The shard reappeared -- the next miss is a first miss again, not a second.
        assertFalse(store.recordNotFound(562, 354))
    }

    @Test
    fun `two different shards keep separate streaks`() {
        val store = PointNotFoundStore(FakePrefs())
        assertFalse(store.recordNotFound(562, 354))  // 354's first miss
        assertFalse(store.recordNotFound(562, 355))  // 355's first miss -- unaffected by 354's streak
        assertTrue(store.recordNotFound(562, 354))   // 354's second miss
        assertTrue(store.recordNotFound(562, 355))   // 355's second miss, independently
    }

    @Test
    fun `it persists across a new store instance over the same prefs`() {
        val prefs = FakePrefs()
        assertFalse(PointNotFoundStore(prefs).recordNotFound(562, 354))
        // A new process, same on-disk prefs: the streak must survive.
        assertTrue(PointNotFoundStore(prefs).recordNotFound(562, 354))
    }
}
