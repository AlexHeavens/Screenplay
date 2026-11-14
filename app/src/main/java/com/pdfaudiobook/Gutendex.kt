/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A request to the catalogue failed; [message] is written for the reader, [retryAfterMs] is the server's own "come back later", when it gave one. */
class CatalogueException(message: String, val retryAfterMs: Long? = null) : Exception(message)

/**
 * Searches Project Gutenberg's catalogue through Gutendex, a free third-party JSON service over that catalogue. The app never loads
 * gutenberg.org pages: it asks Gutendex for a list, and fetches one book's EPUB only when the reader taps it. See docs/gutenberg.md.
 */
object Gutendex {
    /** The only place the service's address is written, so a different host can replace it. */
    const val BASE_URL = "https://gutendex.com/books/"
    const val SITE_URL = "https://www.gutenberg.org/"
    const val PROJECT_URL = "https://github.com/AlexHeavens/Screenplay"

    /** Gutenberg asks every automated client to name itself and give a contact address. */
    fun userAgent(version: String) = "Screenplay/$version (+$PROJECT_URL)"

    /** A genre the reader can pick: [topic] is matched by Gutendex against each book's subjects and bookshelves. */
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
     * Gutenberg has no ratings. The one number it publishes per book is how often it was downloaded, so that is what "popular" means
     * here. The limits come from two real first pages (2026-11-14): the 32 Dickens books had 2,435 to 50,704 downloads (11 at 5,000 or more, 4 at
     * 20,000 or more) and the 32 most downloaded English science fiction books had 6,782 to 182,729 (all at 5,000 or more, 9 at 20,000 or more).
     */
    enum class Popularity(val label: String, val minDownloads: Int) {
        ANY("Any", 0),
        POPULAR("Popular", 5_000),
        VERY_POPULAR("Very popular", 20_000),
    }

    data class Query(
        val text: String = "",
        val genre: Genre? = null,
        val englishOnly: Boolean = true,
        val page: Int = 1,
    )

    data class Entry(val id: Int, val title: String, val author: String, val languages: List<String>, val downloads: Int, val epubUrl: String?)

    data class Page(val entries: List<Entry>, val total: Int, val hasNext: Boolean)

    /** The catalogue request for [query]; most popular first, which is the order the popularity filter relies on. */
    fun url(query: Query): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val parts = buildList {
            query.text.trim().takeIf { it.isNotEmpty() }?.let { add("search=${enc(it)}") }
            query.genre?.let { add("topic=${enc(it.topic)}") }
            if (query.englishOnly) add("languages=en")
            add("sort=popular")
            if (query.page > 1) add("page=${query.page}")
        }
        return BASE_URL + "?" + parts.joinToString("&")
    }

    /** Keeps the entries at or above [popularity]. */
    fun filter(entries: List<Entry>, popularity: Popularity) = entries.filter { it.downloads >= popularity.minDownloads }

    /** A page is sorted by downloads, so once its last entry is below the limit every later page is too: no point offering more. */
    fun hasMore(page: Page, popularity: Popularity) = page.hasNext && (page.entries.lastOrNull()?.downloads ?: 0) >= popularity.minDownloads

    /** "Dickens, Charles" is shown as "Charles Dickens". */
    fun displayAuthor(name: String): String {
        val parts = name.split(", ")
        return if (parts.size == 2) "${parts[1]} ${parts[0]}" else name
    }

    /**
     * The books in a Gutendex response. Books still in copyright and anything that is not text (audio, scores) are left out. An entry
     * without an EPUB stays, with a null [Entry.epubUrl], so the list can say why it cannot be added.
     */
    fun parse(json: String): Page {
        val root = JSONObject(json)
        val results = root.getJSONArray("results")
        val entries = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { !it.optBoolean("copyright") && it.optString("media_type", "Text") == "Text" }
            .map { book ->
                val authors = book.optJSONArray("authors")
                val languages = book.optJSONArray("languages")
                Entry(
                    id = book.getInt("id"),
                    title = book.getString("title"),
                    author = (if (authors != null && authors.length() > 0) displayAuthor(authors.getJSONObject(0).getString("name")) else "Unknown author"),
                    languages = if (languages == null) emptyList() else List(languages.length()) { languages.getString(it) },
                    downloads = book.optInt("download_count"),
                    epubUrl = epubUrl(book.optJSONObject("formats")),
                )
            }
        return Page(entries, root.optInt("count", entries.size), !root.isNull("next") && root.optString("next").isNotEmpty())
    }

    /** The book's EPUB link, or null when it has none or the link does not lead to gutenberg.org over https: nothing else is ever fetched. */
    fun epubUrl(formats: JSONObject?): String? {
        val key = formats?.keys()?.asSequence()?.firstOrNull { it.startsWith("application/epub+zip") } ?: return null
        val url = formats.getString(key)
        val host = try { URL(url).host } catch (_: IOException) { return null }
        return url.takeIf { it.startsWith("https://") && (host == "gutenberg.org" || host.endsWith(".gutenberg.org")) }
    }

    /**
     * The default of [Engines.httpGet]: one GET, the body copied to [out] in chunks, each reported to [onProgress] with the length the server
     * announced (-1 when it did not). Gutendex can take a minute to answer, so the read timeout is long.
     */
    suspend fun fetch(url: String, userAgent: String, out: OutputStream, onProgress: (received: Long, expected: Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                throw CatalogueException(
                    if (code == 429) "The book catalogue says there have been too many requests. Wait a little, then try again."
                    else "The book catalogue answered with an error ($code). Try again later.",
                    retryAfter,
                )
            }
            val expected = connection.contentLengthLong
            val chunk = ByteArray(CHUNK_BYTES)
            var received = 0L
            connection.inputStream.use { body ->
                while (true) {
                    val read = body.read(chunk)
                    if (read < 0) break
                    out.write(chunk, 0, read)
                    received += read
                    onProgress(received, expected)
                }
            }
        } catch (e: IOException) {
            throw CatalogueException("Could not reach the book catalogue. Check the connection and try again.")
        } finally {
            connection.disconnect()
        }
    }

    private const val CHUNK_BYTES = 16 * 1024

    /** The shared client, made on first use so it can name the installed version; a test sets it to start with an empty cache and no waiting. */
    var client: CatalogueClient? = null

    fun client(ctx: android.content.Context): CatalogueClient = client ?: CatalogueClient(
        userAgent(ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"),
    ).also { client = it }
}

/** How long to leave the network alone after a failure: it doubles with each failure in a row, up to a minute, or follows the server's Retry-After. */
class Backoff(private val baseMs: Long = 2_000, private val maxMs: Long = 60_000) {
    private var failures = 0
    private var until = 0L

    fun waitMs(now: Long) = (until - now).coerceAtLeast(0)

    fun failed(now: Long, retryAfterMs: Long? = null) {
        failures = (failures + 1).coerceAtMost(16)
        until = now + (retryAfterMs ?: (baseMs shl (failures - 1))).coerceAtMost(maxMs)
    }

    fun succeeded() { failures = 0; until = 0 }
}

/** Remembers answers for [ttlMs], so going back to a search does not ask again; keeps at most [capacity] of them. */
class ResponseCache<T>(private val ttlMs: Long = 5 * 60_000, private val capacity: Int = 20) {
    private val entries = object : LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, T>>) = size > capacity
    }

    @Synchronized fun get(key: String, now: Long): T? = entries[key]?.takeIf { now - it.first < ttlMs }?.second

    @Synchronized fun put(key: String, value: T, now: Long) { entries[key] = now to value }
}

/** Searches the catalogue and fetches EPUBs, one request per call, through [Engines.httpGet]; waits out a [Backoff] instead of retrying. */
class CatalogueClient(private val userAgent: String, private val now: () -> Long = System::currentTimeMillis) {
    private val cache = ResponseCache<Gutendex.Page>()
    private val backoff = Backoff()

    suspend fun search(query: Gutendex.Query): Gutendex.Page {
        val url = Gutendex.url(query)
        cache.get(url, now())?.let { return it }
        val body = java.io.ByteArrayOutputStream()
        request(url, body)
        return try { Gutendex.parse(body.toString("UTF-8")) } catch (e: org.json.JSONException) {
            throw CatalogueException("The book catalogue sent an answer this app cannot read.")
        }.also { cache.put(url, it, now()) }
    }

    /** Saves the book's EPUB to [file]; a file left by a failed download is removed. */
    suspend fun download(entry: Gutendex.Entry, file: File, onProgress: (received: Long, expected: Long) -> Unit = { _, _ -> }) {
        val url = entry.epubUrl ?: throw CatalogueException("“${entry.title}” has no EPUB, so it cannot be added.")
        download(url, file, onProgress)
    }

    /** How long a request must still wait after a failure; the queue waits this out rather than failing a book. */
    fun backoffMs(): Long = backoff.waitMs(now())

    /**
     * Saves [url] to [file]. The body goes to a temporary name beside it and is renamed once whole, so a download cut short by a
     * stopped worker or a killed app never leaves a partial file that looks like a book; a failed one removes its temporary file.
     * [onProgress] hears the bytes received and the length announced as the body arrives.
     */
    suspend fun download(url: String, file: File, onProgress: (received: Long, expected: Long) -> Unit = { _, _ -> }) {
        val partial = File(file.parentFile, file.name + ".part")
        try {
            partial.outputStream().use { request(url, it, onProgress) }
            java.nio.file.Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    private suspend fun request(url: String, out: OutputStream, onProgress: (received: Long, expected: Long) -> Unit = { _, _ -> }) {
        val wait = backoff.waitMs(now())
        if (wait > 0) throw CatalogueException("Please wait ${(wait + 999) / 1000} seconds before trying again.")
        try {
            Engines.httpGet(url, userAgent, out, onProgress)
            backoff.succeeded()
        } catch (e: CatalogueException) {
            backoff.failed(now(), e.retryAfterMs)
            throw e
        }
    }
}
