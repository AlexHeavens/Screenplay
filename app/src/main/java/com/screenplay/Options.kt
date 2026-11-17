/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.content.SharedPreferences

/** What to do to the text of a new book. Chosen on the shelf, kept across launches, read when a book starts to be made. */
data class Options(
    val spell: Boolean = true,
    val trimFront: Boolean = true,
    val mergeFragments: Boolean = true,
    /** Drop back matter, tables, lists and page numbers. */
    val cleanText: Boolean = true,
    /** Keep a scanned book's footnotes as quiet annotations under their paragraphs, never spoken, instead of reading them in the text. */
    val footnotes: Boolean = true,
    /** Keep a contents page out of the speech and the progress, but show it at the start of the reading view. Off: it is read like any page. */
    val contents: Boolean = true,
    val voice: Voice = Voice.PIPER,
    /** Look of the app, not an import option: kept here so it shares the settings store. */
    val theme: ThemeMode = ThemeMode.SYSTEM,
) {
    fun save(ctx: Context) = save(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

    /** [save] on any store, so a test can drive it without a device. */
    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean("spell", spell).putBoolean("trim", trimFront).putBoolean("merge", mergeFragments)
            .putBoolean("clean", cleanText).putBoolean("footnotes", footnotes).putBoolean("contents", contents).putString("voice", voice.name).putString("theme", theme.name).apply()
    }

    companion object {
        /**
         * A saved name; the first build had a Natural voice switch instead: on meant Kokoro, off the phone's voice.
         * With neither stored, the default.
         */
        private fun voiceOf(name: String?, legacyNatural: Boolean?) =
            Voice.values().firstOrNull { it.name == name }
                ?: legacyNatural?.let { if (it) Voice.KOKORO else Voice.PHONE } ?: Options().voice

        fun load(ctx: Context): Options = load(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

        /** [load] on any store, so a test can drive it without a device. */
        fun load(p: SharedPreferences): Options {
            val d = Options()
            return Options(
                spell = p.getBoolean("spell", d.spell), trimFront = p.getBoolean("trim", d.trimFront),
                mergeFragments = p.getBoolean("merge", d.mergeFragments), cleanText = p.getBoolean("clean", d.cleanText),
                footnotes = p.getBoolean("footnotes", d.footnotes), contents = p.getBoolean("contents", d.contents),
                voice = voiceOf(p.getString("voice", null), if (p.contains("natural")) p.getBoolean("natural", false) else null),
                theme = ThemeMode.values().firstOrNull { it.name == p.getString("theme", null) } ?: d.theme,
            )
        }
    }
}
