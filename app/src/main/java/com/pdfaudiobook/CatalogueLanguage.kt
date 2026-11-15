/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The language "Catalogue" lists books in. One setting: the options and the Catalogue's language menu both read and change it, so they never differ.
 * Until the reader chooses, it is [ALL].
 * It is kept apart from [Options] so that saving an option never writes back an older choice.
 */
object CatalogueLanguage {
    /** The saved choice meaning every language; not a Gutenberg code. */
    const val ALL = "all"
    private const val KEY = "language"

    private val saved = MutableStateFlow<String?>(null)

    /** The reader's choice: a language code, [ALL], or null when none was made yet. */
    val chosen: StateFlow<String?> = saved

    fun load(ctx: Context) { saved.value = prefs(ctx).getString(KEY, null) }

    fun choose(ctx: Context, code: String) {
        saved.value = code
        prefs(ctx).edit().putString(KEY, code).apply()
    }

    /** Forgets the choice, so it is [ALL] again; a test starts from here. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
        saved.value = null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The code to search with, or null for every language, given the [choice] and the codes the book list [available] has. */
    fun effective(choice: String?, available: List<String>): String? =
        if (choice == null || choice == ALL) null else choice.takeIf { available.isEmpty() || it in available }

    /** The languages in the order of the menus: most books first, then by name. */
    fun ordered(languages: List<LanguageCount>): List<LanguageCount> = languages.sortedWith(compareByDescending<LanguageCount> { it.count }.thenBy { name(it.code) })

    /** The language's name in the system language, "French" or "français"; the code in capitals when the system does not know it. */
    fun name(code: String): String {
        val locale = Locale.getDefault()
        val shown = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        return if (shown.isBlank() || shown.equals(code, ignoreCase = true)) code.uppercase() else shown.replaceFirstChar { it.titlecase(locale) }
    }

    /** The label of a choice already turned into a code ([effective]). */
    fun label(code: String?) = code?.let(::name) ?: "All"
}
