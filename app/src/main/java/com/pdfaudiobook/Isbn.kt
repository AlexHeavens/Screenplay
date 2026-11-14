/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A book's title and author, as a catalogue gives them for an ISBN. */
data class BookInfo(val title: String, val author: String)

/** Finds an ISBN in OCR text and checks its check digit, so a stray number is not taken for one. */
object Isbn {
    /** The text after an "ISBN" label (also "ISBN-13:", "ISBN 10"), up to 17 digits, hyphens or spaces. */
    private val labelled = Regex("isbn(?:[- ]?(?:10|13))?\\s*[:.]?\\s*([0-9Xx](?:[- ]?[0-9Xx]){9,16})", RegexOption.IGNORE_CASE)

    /** A 13-digit number with a book prefix, with or without hyphens, when the label was lost to OCR. */
    private val bare = Regex("(?<![0-9])97[89](?:[- ]?[0-9]){10}(?![0-9])")

    /** The first valid ISBN in [text] as digits only (the last of an ISBN-10 may be X), or null. */
    fun find(text: String): String? {
        for (m in labelled.findAll(text)) {
            val chars = m.groupValues[1].filter { it.isLetterOrDigit() }.uppercase()
            // Digits after the number (a year, a price) can follow it, so read the length its prefix says.
            val n = if (chars.startsWith("978") || chars.startsWith("979")) 13 else 10
            chars.take(n).takeIf { valid(it) }?.let { return it }
        }
        for (m in bare.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (valid(digits)) return digits
        }
        return null
    }

    /** Whether [isbn] (digits, no hyphens) is a well-formed ISBN-10 or ISBN-13 with the right check digit. */
    fun valid(isbn: String): Boolean = when (isbn.length) {
        10 -> isbn.dropLast(1).all { it.isDigit() } && (isbn.last().isDigit() || isbn.last() == 'X') &&
            isbn.withIndex().sumOf { (i, c) -> (10 - i) * (if (c == 'X') 10 else c - '0') } % 11 == 0
        13 -> isbn.all { it.isDigit() } &&
            isbn.withIndex().sumOf { (i, c) -> (if (i % 2 == 0) 1 else 3) * (c - '0') } % 10 == 0
        else -> false
    }

    /** Reads the title and authors from an Open Library "books" response for [isbn], or null when it has no such book. */
    fun parse(isbn: String, json: String): BookInfo? {
        val book = JSONObject(json).optJSONObject("ISBN:$isbn") ?: return null
        val title = book.optString("title").trim().ifEmpty { return null }
        val subtitle = book.optString("subtitle").trim()
        val authors = book.optJSONArray("authors")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("name").trim() } }
            ?.filter { it.isNotEmpty() }.orEmpty()
        return BookInfo(if (subtitle.isEmpty()) title else "$title: $subtitle", authors.joinToString(", "))
    }

    /** Asks Open Library, which needs no key. Throws when the network or the reply fails; the caller keeps the old title. */
    fun openLibrary(isbn: String): BookInfo? {
        val c = URL("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data").openConnection() as HttpURLConnection
        try {
            // Open Library asks callers to say who they are, with a way to reach them.
            c.setRequestProperty("User-Agent", "Screenplay (+${Gutenberg.PROJECT_URL})")
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            return parse(isbn, c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
