/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.content.SharedPreferences

/**
 * The chapter summaries' settings, kept in the settings store beside the options but outside [Options]: a book is not made differently
 * by them. The key is the user's own Anthropic API key; it stays on this device (backups are off) and is never logged.
 */
data class SummarySettings(val enabled: Boolean = false, val audio: Boolean = false, val apiKey: String = "", val keyChecked: Boolean = false) {
    /** Whether the summaries can be made: they are on, and the key has been checked and not found rejected since. */
    val usable get() = enabled && keyChecked

    /** On, but the key does not work (it was rejected, or edited and not checked again): the app warns of it. */
    val warn get() = enabled && !keyChecked

    fun save(ctx: Context) = save(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

    /** [save] on any store, so a test can drive it without a device. */
    fun save(prefs: SharedPreferences) {
        prefs.edit().putBoolean("summaries", enabled).putBoolean("summary-audio", audio).putString("anthropic-key", apiKey.trim())
            .putBoolean("anthropic-key-checked", keyChecked).apply()
    }

    companion object {
        fun load(ctx: Context): SummarySettings = load(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

        fun load(p: SharedPreferences): SummarySettings {
            val d = SummarySettings()
            return SummarySettings(p.getBoolean("summaries", d.enabled), p.getBoolean("summary-audio", d.audio), p.getString("anthropic-key", d.apiKey).orEmpty(), p.getBoolean("anthropic-key-checked", d.keyChecked))
        }

        /** Records what a check of the stored key found, for the key as it is now: a rejected key turns the warning on, a working one off. */
        fun markChecked(ctx: Context, ok: Boolean) = load(ctx).let { if (it.keyChecked != ok) it.copy(keyChecked = ok).save(ctx) }
    }
}
