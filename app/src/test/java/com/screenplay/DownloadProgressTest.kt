/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    private val megabyte = 1024L * 1024L

    @Test
    fun aKnownLengthGivesAFractionAndAPercentWithBothSizes() {
        val progress = DownloadProgress(received = 3 * megabyte + megabyte / 10, expected = 7 * megabyte + 4 * megabyte / 10)
        assertEquals(0.42f, progress.fraction!!, 0.01f)
        assertEquals("Downloading 41% · 3.1 of 7.4 MB", progress.label)
    }

    @Test
    fun anUnknownLengthGivesNoFractionAndOnlyTheBytesReceived() {
        assertNull(DownloadProgress(megabyte, -1).fraction)
        assertNull(DownloadProgress(megabyte, 0).fraction)
        assertEquals("Downloading 1.0 MB", DownloadProgress(megabyte, -1).label)
        assertEquals("Downloading 20 KB", DownloadProgress(20 * 1024, -1).label)
        assertEquals("Downloading…", DownloadProgress(0, -1).label)
    }

    @Test
    fun theFractionAndPercentStayWithinTheBarWhenTheServerSendsMoreThanItAnnounced() {
        val over = DownloadProgress(received = 2_000, expected = 1_000)
        assertEquals(1f, over.fraction!!, 0f)
        assertTrue(over.label, over.label.startsWith("Downloading 100% "))
        assertEquals(0f, DownloadProgress(0, 1_000).fraction!!, 0f)
    }

    @Test
    fun theThrottlePassesOnlyReportsThatChangeWhatTheReaderSees() {
        val throttle = DownloadProgress.Throttle()
        val total = 50 * megabyte
        var passed = 0
        // One report per 16 KB chunk, as the real download makes: about 3,200 chunks, but the label has about 100 distinct whole percents.
        for (received in 0..total step 16 * 1024) if (throttle.accept(DownloadProgress(received, total))) passed++
        assertTrue("passed $passed reports", passed in 100..110)
        assertFalse("the same label again is dropped", throttle.accept(DownloadProgress(total, total).copy()))
        assertTrue(throttle.accept(DownloadProgress(total, total + megabyte)))
    }

    @Test
    fun theThrottleHandlesAnUnknownLengthByTenthsOfAMegabyte() {
        val throttle = DownloadProgress.Throttle()
        var passed = 0
        for (received in 0..2 * megabyte step 1024) if (throttle.accept(DownloadProgress(received, -1))) passed++
        assertTrue("passed $passed reports", passed in 20..40)
    }

    @Test
    fun aRetryTellsItsTryNumberUntilTheFirstByteArrives() {
        assertEquals("Downloading…", DownloadProgress(0, -1).label)
        assertEquals("Retrying download… (try 2 of 3)", DownloadProgress(0, -1, attempt = 2).label)
        assertEquals("try 2 of 3", DownloadProgress(0, -1, attempt = 2).counts)
    }
}
