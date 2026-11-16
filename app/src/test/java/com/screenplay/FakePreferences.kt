/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.SharedPreferences

/** An in-memory [SharedPreferences] that, like the real one, throws when a key holds another type than the getter asks for. */
class FakePreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    private val values = LinkedHashMap<String, Any>().apply { initial.forEach { (k, v) -> if (v != null) put(k, v) } }

    /** The stored key/value map as it would be written to the settings file. */
    fun snapshot(): Map<String, Any> = LinkedHashMap(values)

    private inline fun <reified T> typed(key: String, default: T): T {
        val v = values[key] ?: return default
        if (v !is T) throw ClassCastException("$key holds ${v::class.simpleName}, not ${T::class.simpleName}")
        return v
    }

    override fun getAll(): Map<String, *> = snapshot()
    override fun getString(key: String, defValue: String?): String? = typed(key, defValue)
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = typed(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = typed(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = typed(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = typed(key, defValue)
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clear = false
        private fun set(key: String, value: Any?) = apply { pending[key] = value }
        override fun putString(key: String, value: String?) = set(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = set(key, values)
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun putLong(key: String, value: Long) = set(key, value)
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) = set(key, null)
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}
