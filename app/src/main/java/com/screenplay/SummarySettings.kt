/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.content.SharedPreferences

/** The chapter summaries' settings, kept in the settings store beside the options but outside [Options]: a book is not made differently by them. */
data class SummarySettings(val enabled: Boolean = false, val audio: Boolean = false) {
    fun save(ctx: Context) = save(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

    /** [save] on any store, so a test can drive it without a device. */
    fun save(prefs: SharedPreferences) {
        prefs.edit().putBoolean("summaries", enabled).putBoolean("summary-audio", audio).apply()
    }

    companion object {
        fun load(ctx: Context): SummarySettings = load(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

        fun load(p: SharedPreferences): SummarySettings {
            val d = SummarySettings()
            return SummarySettings(p.getBoolean("summaries", d.enabled), p.getBoolean("summary-audio", d.audio))
        }
    }
}
