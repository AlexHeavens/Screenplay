/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipTest {
    @Test fun theStepIsTenSeconds() {
        assertEquals(10_000L, SKIP_MS)
    }

    @Test fun skipsByTheStepInEitherDirection() {
        assertEquals(35_000L, skippedTo(25_000, SKIP_MS, 100_000))
        assertEquals(15_000L, skippedTo(25_000, -SKIP_MS, 100_000))
    }

    @Test fun stopsAtTheStartAndAtTheEndOfTheAudio() {
        assertEquals(0L, skippedTo(5_000, -SKIP_MS, 100_000))
        assertEquals(100_000L, skippedTo(95_000, SKIP_MS, 100_000))
    }

    @Test fun anEmptyBookStaysAtZero() {
        assertEquals(0L, skippedTo(0, SKIP_MS, 0))
    }
}
