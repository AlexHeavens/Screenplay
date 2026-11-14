/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A request to the catalogue failed; [message] is written for the reader, [retryAfterMs] is the server's own "come back later", when it gave one. */
class CatalogueException(message: String, val retryAfterMs: Long? = null) : Exception(message)

/**
 * Project Gutenberg as the app meets it: the one place its addresses are written, the user agent every request carries, and the download
 * of a book's EPUB, which happens only when a queued book's turn comes. The book list itself is searched locally ([Catalogue]); see docs/gutenberg.md.
 */
object Gutenberg {
    const val SITE_URL = "https://www.gutenberg.org/"
    const val PROJECT_URL = "https://github.com/AlexHeavens/Screenplay"

    /** Gutenberg asks every automated client to name itself and give a contact address. */
    fun userAgent(version: String) = "Screenplay/$version (+$PROJECT_URL)"

    /** The book's EPUB with images, the link Gutenberg's own catalogue gives; only this https address on gutenberg.org is ever fetched for a book. */
    fun epubUrl(id: Int) = "${SITE_URL}ebooks/$id.epub3.images"

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
class CatalogueClient(private val userAgent: String, private val now: () -> Long = System::currentTimeMillis) {
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
