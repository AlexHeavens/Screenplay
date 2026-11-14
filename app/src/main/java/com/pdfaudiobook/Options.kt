/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context

/** What to do to the text of a new book. Chosen on the shelf, kept across launches, read when a book starts to be made. */
data class Options(
    val spell: Boolean = false,
    val trimFront: Boolean = true,
    val mergeFragments: Boolean = true,
    /** Drop back matter, tables, lists and page numbers. */
    val cleanText: Boolean = true,
    val voice: Voice = Voice.PHONE,
    /** Look of the app, not an import option: kept here so it shares the settings store. */
    val theme: ThemeMode = ThemeMode.SYSTEM,
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("spell", spell).putBoolean("trim", trimFront).putBoolean("merge", mergeFragments)
            .putBoolean("clean", cleanText).putString("voice", voice.name).putString("theme", theme.name).apply()
    }

    companion object {
        /** A saved name; the first build had a Natural voice switch instead, which meant Kokoro. */
        private fun voiceOf(name: String?, legacyNatural: Boolean) =
            Voice.values().firstOrNull { it.name == name } ?: if (legacyNatural) Voice.KOKORO else Voice.PHONE

        fun load(ctx: Context): Options {
            val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val d = Options()
            return Options(
                p.getBoolean("spell", d.spell), p.getBoolean("trim", d.trimFront),
                p.getBoolean("merge", d.mergeFragments), p.getBoolean("clean", d.cleanText), voiceOf(p.getString("voice", null), p.getBoolean("natural", false)),
                ThemeMode.values().firstOrNull { it.name == p.getString("theme", null) } ?: d.theme,
            )
        }
    }
}
