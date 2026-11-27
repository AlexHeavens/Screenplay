/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A request to the catalogue failed; [message] is written for the reader, [retryAfterMs] is the server's own "come back later", when it gave one.
 * [transient] is true when the same request may well work a moment later (no connection, too many requests, a server error), false when asking again
 * cannot help (the book is not there): [QueueDownload] retries the first kind by itself and fails the book at once on the second.
 */
class CatalogueException(message: String, val retryAfterMs: Long? = null, val transient: Boolean = false) : Exception(message)

/**
 * Project Gutenberg as the app meets it: the one place its addresses are written, the user agent every request carries, and the download
 * of a book's EPUB, which happens only when a queued book's turn comes. The book list itself is searched locally ([Catalogue]); see docs/gutenberg.md.
 */
object Gutenberg {
    const val SITE_URL = "https://www.gutenberg.org/"
    const val PROJECT_URL = "https://github.com/AlexHeavens/Screenplay"

    /** Gutenberg asks every automated client to name itself and give a contact address. */
    fun userAgent(version: String) = "Screenplay/$version (+$PROJECT_URL)"

    /** Gutenberg's own mirror, run by the Project Gutenberg Literary Archive Foundation; it holds the same files and stays up when [SITE_URL] is down. */
    const val MIRROR_URL = "https://gutenberg.pglaf.org/"

    /** The book's EPUB with images, the link Gutenberg's own catalogue gives; this is the address a queued book keeps, and [routed] says where it is fetched from. */
    fun epubUrl(id: Int) = "${SITE_URL}ebooks/$id.epub3.images"

    private val epubUrlPattern = Regex("^" + Regex.escape(SITE_URL) + "ebooks/(\\d+)\\.epub3\\.images$")

    /** Which of the two sites answered the last [probe]; both are assumed up until one has been made. */
    @Volatile private var siteUp = true
    @Volatile private var mirrorUp = true
    @Volatile private var probedAt = 0L

    /**
     * Asks both sites whether they answer, unless a probe was made less than [maxAgeMs] ago. The "Catalogue" screen probes each time it opens
     * (maxAgeMs 0); a book's turn in the queue probes only when the last probe is old, so a book queued long ago does not go to a site found down hours before.
     */
    suspend fun probe(agent: String, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - probedAt < maxAgeMs) return
        coroutineScope {
            val site = async { Engines.httpProbe(SITE_URL, agent) }
            val mirror = async { Engines.httpProbe(MIRROR_URL, agent) }
            siteUp = site.await()
            mirrorUp = mirror.await()
        }
        probedAt = now
    }

    /** [url] as it is fetched: a book's EPUB link goes to the mirror when the last probe found [SITE_URL] down and the mirror up; any other address, or any other answer, leaves it as it is. */
    fun routed(url: String): String {
        val id = epubUrlPattern.matchEntire(url)?.groupValues?.get(1) ?: return url
        return if (!siteUp && mirrorUp) "${MIRROR_URL}cache/epub/$id/pg$id-images-3.epub" else url
    }

    /** Forgets the last probe, so both sites are assumed up again; a test starts from here. */
    fun forgetProbe() { siteUp = true; mirrorUp = true; probedAt = 0L }

    /** The default of [Engines.httpProbe]: one HEAD request; a site is up when it answers 2xx or 3xx, and down on any other status, a timeout or a network error. */
    suspend fun headSucceeds(url: String, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.responseCode in 200..399
        } catch (e: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private const val PROBE_TIMEOUT_MS = 8_000

    /**
     * The default of [Engines.httpGet]: one GET, the body copied to [out] in chunks, each reported to [onProgress] with the length the server
     * announced (-1 when it did not). The read timeout is long because a book file is large and a mirror can be slow.
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
                val transient = code == 408 || code == 429 || code >= 500
                throw CatalogueException(
                    when {
                        code == 429 -> "The book catalogue says there have been too many requests. Wait a little, then try again."
                        transient -> "The book catalogue answered with an error ($code). Try again later."
                        else -> "The book catalogue has no such file (error $code)."
                    },
                    retryAfter,
                    transient,
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
            throw CatalogueException("Could not reach the book catalogue. Check the connection and try again.", transient = true)
        } finally {
            connection.disconnect()
        }
    }

    private const val CHUNK_BYTES = 16 * 1024

    /** The shared client, made on first use so it can name the installed version; a test sets it to start with no waiting. */
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

/** Fetches EPUBs, one request per call, through [Engines.httpGet]; waits out a [Backoff] instead of retrying. */
class CatalogueClient(val userAgent: String, private val now: () -> Long = System::currentTimeMillis) {
    private val backoff = Backoff()

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
        if (wait > 0) throw CatalogueException("Please wait ${(wait + 999) / 1000} seconds before trying again.", transient = true)
        try {
            Engines.httpGet(url, userAgent, out, onProgress)
            backoff.succeeded()
        } catch (e: CatalogueException) {
            backoff.failed(now(), e.retryAfterMs)
            throw e
        }
    }
}
