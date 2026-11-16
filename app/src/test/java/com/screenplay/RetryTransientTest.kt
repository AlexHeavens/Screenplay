/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RetryTransientTest {
    private fun transient() = CatalogueException("no connection", transient = true)

    @Test
    fun aTransientFailureIsTriedAgainAndTheBookCarriesOnWhenALaterTryWorks() = runBlocking {
        var tries = 0
        QueueDownload.retryTransient(3) { if (++tries < 3) throw transient() }
        assertEquals(3, tries)
    }

    @Test
    fun aTransientFailureThatKeepsComingIsThrownAfterTheLastTry() = runBlocking {
        var tries = 0
        try { QueueDownload.retryTransient(3) { tries++; throw transient() }; fail("expected the failure") } catch (e: CatalogueException) { assertEquals("no connection", e.message) }
        assertEquals(3, tries)
    }

    @Test
    fun aPermanentFailureIsThrownAtOnceWithoutAnotherTry() = runBlocking {
        var tries = 0
        try { QueueDownload.retryTransient(3) { tries++; throw CatalogueException("no such file (error 404)") }; fail("expected the failure") } catch (e: CatalogueException) { assertEquals("no such file (error 404)", e.message) }
        assertEquals(1, tries)
    }
}
