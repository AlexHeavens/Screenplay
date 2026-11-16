/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import org.tukaani.xz.XZInputStream

/**
 * A genre the reader can pick: [topic] is matched against each book's bookshelves and subjects, ignoring case. A book's genres are
 * one bit each (`1 shl ordinal`) in an Int that is built when the file is read, never stored, so there is room for 32 genres: new ones
 * go at the end.
 */
enum class Genre(val label: String, val topic: String) {
    FICTION("Fiction", "fiction"),
    SCIENCE_FICTION("Science fiction", "science fiction"),
    MYSTERY("Mystery", "mystery"),
    ADVENTURE("Adventure", "adventure"),
    POETRY("Poetry", "poetry"),
    DRAMA("Drama", "drama"),
    HISTORY("History", "history"),
    PHILOSOPHY("Philosophy", "philosophy"),
    CHILDREN("Children's", "children"),
    BIOGRAPHY("Biography", "biograph"),
    ESSAYS("Essays and speeches", "essays"),
    HISTORICAL_NOVELS("Historical novels", "historical novels"),
    HUMOUR("Humour", "humour"),
    SHORT_STORIES("Short stories", "short stories"),
    RELIGION("Religion and spirituality", "religion"),
    TRAVEL("Travel", "travel"),
    MYTHOLOGY("Mythology and folklore", "folklore"),
    WARFARE("Warfare", "warfare"),
    ROMANCE("Romance", "romance"),
    HOW_TO("How-to", "how to"),
    NATURE("Nature", "nature"),
}

/**
 * The order of the matching books. Gutenberg has no ratings; the one number it publishes per book is how often it was downloaded,
 * so [DOWNLOADS] is the measure of how read a book is. [RATING] uses the average rating Open Library readers gave the book (the catalogue
 * file carries it, see scripts/build-ratings.py): books with at least [MIN_RATINGS] ratings come first, best average first, then the
 * ones with fewer ratings or none, most downloaded first, so one 5-star vote cannot rank a book first. [TITLE] is the default.
 */
enum class Sort(val label: String) {
    TITLE("Title A–Z"),
    DOWNLOADS("Most downloaded"),
    RATING("Best rated"),
}

/** The fewest ratings a book needs to be ranked by its average. */
const val MIN_RATINGS = 5

data class CatalogueQuery(
    val text: String = "",
    val genre: Genre? = null,
    val sort: Sort = Sort.TITLE,
    /** A Gutenberg language code such as "en"; null searches every language. */
    val language: String? = null,
)

/** How many books of the list are in the language with Gutenberg code [code]. */
data class LanguageCount(val code: String, val count: Int)

/** A book as the list shows it; [author] is already in reading order ("Charles Dickens"). */
data class CatalogueEntry(val id: Int, val title: String, val author: String, val languages: List<String>, val downloads: Int, val rating10: Int = 0, val ratingCount: Int = 0) {
    val epubUrl get() = Gutenberg.epubUrl(id)

    /** The average rating from 1.0 to 5.0, or null when nobody rated the book. */
    val rating: Double? get() = if (ratingCount > 0) rating10 / 10.0 else null
}

/** The first [entries] of the [total] books that match, in the order asked for. */
data class CatalogueResult(val entries: List<CatalogueEntry>, val total: Int)

/**
 * The book list "Catalogue" searches, held in memory: one row per public-domain text book with an EPUB.
 * The file it comes from is built daily from Gutenberg's bulk catalogue by scripts/build-catalogue.py, which also describes the format.
 */
class CatalogueData private constructor(val version: String, private val rows: List<Row>) {
    private class Row(val id: Int, val title: String, val authors: String, val languages: String, val downloads: Int, val search: String, val genres: Int, val rating10: Int, val ratingCount: Int) {
        val ranked get() = ratingCount >= MIN_RATINGS

        /** Whether the book is in [code]; a book in English and French is in both. */
        fun inLanguage(code: String) = languages == code || languages.startsWith("$code,") || languages.endsWith(",$code") || languages.contains(",$code,")
    }

    /** The languages of the books, most books first (ties by code); each book counts once for every language it is in. */
    val languages: List<LanguageCount> by lazy {
        val counts = HashMap<String, Int>()
        for (row in rows) for (code in row.languages.split(',')) if (code.isNotEmpty()) counts.merge(code, 1, Int::plus)
        counts.map { LanguageCount(it.key, it.value) }.sortedWith(compareByDescending<LanguageCount> { it.count }.thenBy { it.code })
    }

    val size get() = rows.size

    /** The same rows by title, built the first time a search asks for the title order. [rows] is by downloads. */
    private val byTitle by lazy { rows.sortedWith(compareBy<Row> { fold(it.title) }.thenBy { it.id }) }

    /** The same rows by rating (see [Sort.RATING]), built the first time a search asks for it. */
    private val byRating by lazy {
        rows.sortedWith(compareByDescending<Row> { it.ranked }.thenByDescending { if (it.ranked) it.rating10 else 0 }.thenByDescending { if (it.ranked) it.ratingCount else 0 }.thenByDescending { it.downloads }.thenBy { it.id })
    }

    /** The books that match [query], up to [limit] of them, and how many matched in all. */
    fun search(query: CatalogueQuery, limit: Int): CatalogueResult {
        val words = fold(query.text).split(' ').filter { it.isNotEmpty() }
        val genre = query.genre?.let { 1 shl it.ordinal } ?: 0
        val found = ArrayList<CatalogueEntry>(limit)
        var total = 0
        for (row in when (query.sort) { Sort.DOWNLOADS -> rows; Sort.RATING -> byRating; Sort.TITLE -> byTitle }) {
            if (query.language != null && !row.inLanguage(query.language)) continue
            if (genre != 0 && row.genres and genre == 0) continue
            if (!words.all { row.search.contains(it) }) continue
            if (found.size < limit) found += CatalogueEntry(row.id, row.title, displayAuthors(row.authors), row.languages.split(','), row.downloads, row.rating10, row.ratingCount)
            total++
        }
        return CatalogueResult(found, total)
    }

    companion object {
        /** The newest file format this reads. Format 1 has no rating columns, so every book in it is unrated. */
        const val FORMAT = 2

        /** The one place that knows the file's compression (xz, from org.tukaani:xz). A damaged or other file throws an [java.io.IOException]. */
        private fun decompress(input: InputStream): InputStream = XZInputStream(input)

        /** Reads the file's text: the version line, the tag line, then one tab-separated row per book. Anything else is a [CatalogueException]. */
        fun parse(input: InputStream): CatalogueData {
            val lines = decompress(input).bufferedReader(Charsets.UTF_8)
            val unreadable = CatalogueException("The book list is not in a form this app can read. Updating the app may fix this.")
            val head = lines.readLine()?.split(' ') ?: throw unreadable
            if (head.size != 4 || head[0] != "catalogue-format" || head[1].toIntOrNull() !in 1..FORMAT || head[2] != "build") throw unreadable
            val tags = lines.readLine()?.split('\t')?.takeIf { it.firstOrNull() == "tags" }?.drop(1) ?: throw unreadable
            val genreOfTag = IntArray(tags.size) { i -> Genre.values().fold(0) { mask, g -> if (tags[i].contains(g.topic, ignoreCase = true)) mask or (1 shl g.ordinal) else mask } }
            val rows = ArrayList<Row>(80_000)
            while (true) {
                val line = lines.readLine() ?: break
                val f = line.split('\t')
                val format = head[1].toInt()
                // Columns a later version appends to the format are skipped (docs/data-specs.md: additive only within a version).
                if (f.size < if (format == 1) 6 else 8) throw unreadable
                val id = f[0].toIntOrNull() ?: throw unreadable
                val languages = f[3]
                val genres = if (f[5].isEmpty()) 0 else f[5].split(',').fold(0) { mask, n -> mask or (genreOfTag.getOrNull(n.toIntOrNull() ?: -1) ?: throw unreadable) }
                val ratingCount = if (format == 1 || f[7].isEmpty()) 0 else f[7].toIntOrNull() ?: throw unreadable
                val rating10 = if (ratingCount == 0) 0 else f[6].toIntOrNull()?.takeIf { it in 10..50 } ?: throw unreadable
                rows += Row(id, f[1], f[2], languages, f[4].toIntOrNull() ?: throw unreadable, fold("${f[1]} ${f[2]}"), genres, rating10, ratingCount)
            }
            if (rows.isEmpty()) throw unreadable
            rows.sortWith(compareByDescending<Row> { it.downloads }.thenBy { it.id })
            return CatalogueData(head[3], rows)
        }

        /** Lower case without accents, so "cafe" finds "Café". */
        fun fold(text: String): String {
            val lower = text.lowercase()
            return if (lower.all { it.code < 128 }) lower else Normalizer.normalize(lower, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        }

        /** "Dickens, Charles" is shown as "Charles Dickens"; several authors are joined with " & ". */
        fun displayAuthors(authors: String): String {
            if (authors.isEmpty()) return "Unknown author"
            return authors.split("; ").joinToString(" & ") { name ->
                val parts = name.split(", ")
                if (parts.size == 2) "${parts[1]} ${parts[0]}" else name
            }
        }
    }
}

/**
 * The stored copy of the book list. It is downloaded once from this project's GitHub release (one request for the whole file, built daily),
 * kept in the app's files, and replaced at most once a day. A download that fails or does not read as a book list leaves the old copy in place,
 * so searching works offline after the first download. Nothing is fetched until the reader opens "Catalogue".
 */
object Catalogue {
    /** The release tagged `catalogue`, never `releases/latest`, which is the app. */
    const val URL = "https://github.com/AlexHeavens/Screenplay/releases/download/catalogue/catalogue.tsv.xz"
    const val FILE = "catalogue.tsv.xz"
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    sealed interface State {
        data object Opening : State
        data class Downloading(val progress: DownloadProgress) : State
        data class Ready(val data: CatalogueData) : State
        data class Failed(val message: String) : State
    }

    private val current = MutableStateFlow<State>(State.Opening)
    private val one = Mutex()

    /** What the screen shows: the stored list once it is read, or what it is waiting for. */
    val state: StateFlow<State> = current

    /** Forgets the list in memory, so the next [open] reads the file again; a test starts from here. */
    fun reset() { current.value = State.Opening }

    private const val LANGUAGES_KEY = "catalogue-languages"

    /** Keeps the list's languages ("en:60000,fr:2000"), so the options can offer them before the list is read. */
    private fun rememberLanguages(ctx: Context, data: CatalogueData) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(LANGUAGES_KEY, data.languages.joinToString(",") { "${it.code}:${it.count}" }).apply()
    }

    /** The languages of the list, from memory or else from the last time a list was read; empty when none has been. */
    fun knownLanguages(ctx: Context): List<LanguageCount> {
        (current.value as? State.Ready)?.let { return it.data.languages }
        val saved = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(LANGUAGES_KEY, null).orEmpty()
        return saved.split(',').mapNotNull { pair -> pair.split(':').takeIf { it.size == 2 }?.let { (c, n) -> n.toIntOrNull()?.let { LanguageCount(c, it) } } }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /**
     * Makes the list available. With a stored copy it is read at once and a copy older than a day is replaced in the background of this call
     * (the old one stays on screen meanwhile and when the download fails). With none, it is downloaded first.
     */
    suspend fun open(ctx: Context) = one.withLock {
        val file = file(ctx)
        val stored = (current.value as? State.Ready)?.data ?: if (file.exists()) read(file) else null
        current.value = if (stored != null) State.Ready(stored) else State.Opening
        if (stored != null) rememberLanguages(ctx, stored)
        val stale = stored == null || System.currentTimeMillis() - file.lastModified() >= MAX_AGE_MS
        if (!stale) return@withLock
        val throttle = DownloadProgress.Throttle()
        try {
            val fresh = download(ctx, file, onProgress = { if (stored == null && throttle.accept(it)) current.value = State.Downloading(it) })
            rememberLanguages(ctx, fresh)
            current.value = State.Ready(fresh)
        } catch (e: CatalogueException) {
            // An old copy is better than none: say nothing and try again the next time the screen opens.
            if (stored == null) current.value = State.Failed(e.message ?: "The book list could not be downloaded.")
        }
    }

    private suspend fun read(file: File): CatalogueData? = withContext(Dispatchers.IO) {
        try { file.inputStream().use { CatalogueData.parse(it) } } catch (e: Exception) { file.delete(); null }
    }

    /** Downloads beside [file], checks that it reads, and only then replaces [file]; the copy's age is the time of this replacement. */
    private suspend fun download(ctx: Context, file: File, onProgress: (DownloadProgress) -> Unit): CatalogueData {
        val fresh = File(file.parentFile, file.name + ".new")
        try {
            Gutenberg.client(ctx).download(URL, fresh) { received, expected -> onProgress(DownloadProgress(received, expected)) }
            val data = withContext(Dispatchers.IO) {
                try { fresh.inputStream().use { CatalogueData.parse(it) } } catch (e: java.io.IOException) { throw CatalogueException("The book list that was downloaded could not be read.") }
            }
            withContext(Dispatchers.IO) { java.nio.file.Files.move(fresh.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            return data
        } finally {
            fresh.delete()
        }
    }
}
