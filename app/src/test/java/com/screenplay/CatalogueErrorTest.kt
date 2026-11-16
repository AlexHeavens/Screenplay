/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import org.junit.Test

/** What a person reads when the book catalogue says no, is not reachable, or answers nonsense. Uses a real local server and a real closed port. */
class CatalogueErrorTest {
    private fun failureFor(status: Int, retryAfter: String? = null): CatalogueException {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val answering = Thread {
            server.accept().use { socket ->
                socket.getInputStream().bufferedReader().let { reader -> while (reader.readLine().orEmpty().isNotEmpty()) { /* Skip the request headers. */ } }
                val retry = retryAfter?.let { "Retry-After: $it\r\n" }.orEmpty()
                socket.getOutputStream().write("HTTP/1.1 $status Test\r\n${retry}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }.also { it.start() }
        try { return failureAt("http://127.0.0.1:${server.localPort}/books/") } finally { answering.join(5_000); server.close() }
    }

    private fun failureAt(url: String): CatalogueException = runBlocking {
        try { Gutenberg.fetch(url, "Screenplay/test", ByteArrayOutputStream()) { _, _ -> }; fail("expected a failure") } catch (e: CatalogueException) { return@runBlocking e }
        throw AssertionError()
    }

    @Test
    fun tooManyRequestsSaysToWaitAndKeepsTheServersRetryDelay() {
        val failure = failureFor(429, retryAfter = "7")
        assertEquals("The book catalogue says there have been too many requests. Wait a little, then try again.", failure.message)
        assertEquals(7_000L, failure.retryAfterMs)
    }

    @Test
    fun anyOtherErrorStatusIsNamedByItsNumber() {
        val failure = failureFor(503)
        assertEquals("The book catalogue answered with an error (503). Try again later.", failure.message)
        assertNull(failure.retryAfterMs)
    }

    @Test
    fun aCatalogueThatCannotBeReachedSaysToCheckTheConnection() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals("Could not reach the book catalogue. Check the connection and try again.", failureAt("http://127.0.0.1:$closedPort/books/").message)
    }

    @Test
    fun aMissingFileIsPermanentAndNamesItsStatusWhileBusyAndUnreachableAreTransient() {
        val missing = failureFor(404)
        assertEquals("The book catalogue has no such file (error 404).", missing.message)
        assertFalse(missing.transient)
        assertTrue(failureFor(503).transient)
        assertTrue(failureFor(429).transient)
        val closedPort = ServerSocket(0).use { it.localPort }
        assertTrue(failureAt("http://127.0.0.1:$closedPort/books/").transient)
    }
}
