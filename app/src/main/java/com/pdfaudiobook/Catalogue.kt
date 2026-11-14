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

/** A genre the reader can pick: [topic] is matched against each book's bookshelves and subjects, ignoring case. */
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
}

/**
 * Gutenberg has no ratings. The one number it publishes per book is how often it was downloaded, so that is what "popular" means here.
 * The limits come from two real first pages (2026-11-14): the 32 Dickens books had 2,435 to 50,704 downloads (11 at 5,000 or more, 4 at
 * 20,000 or more) and the 32 most downloaded English science fiction books had 6,782 to 182,729 (all at 5,000 or more, 9 at 20,000 or more).
 */
enum class Popularity(val label: String, val minDownloads: Int) {
    ANY("Any", 0),
    POPULAR("Popular", 5_000),
    VERY_POPULAR("Very popular", 20_000),
}

data class CatalogueQuery(
    val text: String = "",
    val genre: Genre? = null,
    val popularity: Popularity = Popularity.ANY,
    val englishOnly: Boolean = true,
)

/** A book as the list shows it; [author] is already in reading order ("Charles Dickens"). */
data class CatalogueEntry(val id: Int, val title: String, val author: String, val languages: List<String>, val downloads: Int) {
    val epubUrl get() = Gutenberg.epubUrl(id)
}

/** The first [entries] of the [total] books that match, most downloaded first. */
data class CatalogueResult(val entries: List<CatalogueEntry>, val total: Int)

/**
 * The book list "Find books" searches, held in memory: one row per public-domain text book with an EPUB, most downloaded first.
 * The file it comes from is built daily from Gutenberg's bulk catalogue by scripts/build-catalogue.py, which also describes the format.
 */
class CatalogueData private constructor(val version: String, private val rows: List<Row>) {
    private class Row(val id: Int, val title: String, val authors: String, val languages: String, val downloads: Int, val search: String, val english: Boolean, val genres: Int)

    val size get() = rows.size

    /** The books that match [query], up to [limit] of them, and how many matched in all. */
    fun search(query: CatalogueQuery, limit: Int): CatalogueResult {
        val words = fold(query.text).split(' ').filter { it.isNotEmpty() }
        val genre = query.genre?.let { 1 shl it.ordinal } ?: 0
        val found = ArrayList<CatalogueEntry>(limit)
        var total = 0
        for (row in rows) {
            if (row.downloads < query.popularity.minDownloads) break // Sorted by downloads, so no later row can match.
            if (query.englishOnly && !row.english) continue
            if (genre != 0 && row.genres and genre == 0) continue
            if (!words.all { row.search.contains(it) }) continue
            if (found.size < limit) found += CatalogueEntry(row.id, row.title, displayAuthors(row.authors), row.languages.split(','), row.downloads)
            total++
        }
        return CatalogueResult(found, total)
    }

    companion object {
        const val FORMAT = 1

        /** The one place that knows the file's compression (xz, from org.tukaani:xz). A damaged or other file throws an [java.io.IOException]. */
        private fun decompress(input: InputStream): InputStream = XZInputStream(input)

        /** Reads the file's text: the version line, the tag line, then one tab-separated row per book. Anything else is a [CatalogueException]. */
        fun parse(input: InputStream): CatalogueData {
            val lines = decompress(input).bufferedReader(Charsets.UTF_8)
            val unreadable = CatalogueException("The book list is not in a form this app can read. Updating the app may fix this.")
            val head = lines.readLine()?.split(' ') ?: throw unreadable
            if (head.size != 4 || head[0] != "catalogue-format" || head[1] != FORMAT.toString() || head[2] != "build") throw unreadable
            val tags = lines.readLine()?.split('\t')?.takeIf { it.firstOrNull() == "tags" }?.drop(1) ?: throw unreadable
            val genreOfTag = IntArray(tags.size) { i -> Genre.values().fold(0) { mask, g -> if (tags[i].contains(g.topic, ignoreCase = true)) mask or (1 shl g.ordinal) else mask } }
            val rows = ArrayList<Row>(80_000)
            while (true) {
                val line = lines.readLine() ?: break
                val f = line.split('\t')
                if (f.size != 6) throw unreadable
                val id = f[0].toIntOrNull() ?: throw unreadable
                val languages = f[3]
                val genres = if (f[5].isEmpty()) 0 else f[5].split(',').fold(0) { mask, n -> mask or (genreOfTag.getOrNull(n.toIntOrNull() ?: -1) ?: throw unreadable) }
                rows += Row(id, f[1], f[2], languages, f[4].toIntOrNull() ?: throw unreadable, fold("${f[1]} ${f[2]}"), languages.split(',').contains("en"), genres)
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
 * so searching works offline after the first download. Nothing is fetched until the reader opens "Find books".
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

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /**
     * Makes the list available. With a stored copy it is read at once and a copy older than a day is replaced in the background of this call
     * (the old one stays on screen meanwhile and when the download fails). With none, it is downloaded first.
     */
    suspend fun open(ctx: Context) = one.withLock {
        val file = file(ctx)
        val stored = (current.value as? State.Ready)?.data ?: if (file.exists()) read(file) else null
        current.value = if (stored != null) State.Ready(stored) else State.Opening
        val stale = stored == null || System.currentTimeMillis() - file.lastModified() >= MAX_AGE_MS
        if (!stale) return@withLock
        val throttle = DownloadProgress.Throttle()
        try {
            current.value = State.Ready(download(ctx, file, onProgress = { if (stored == null && throttle.accept(it)) current.value = State.Downloading(it) }))
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
