/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFeederTest {
    private fun pcm() = File.createTempFile("feeder", ".pcm").apply { writeBytes(ByteArray(64 * 1024)); deleteOnExit() }

    /** Like an AudioTrack: write blocks until stop(), and throws once released. */
    private class BlockingSink : PcmSink {
        val inWrite = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val writingAtRelease = AtomicBoolean(false)
        val writeAfterRelease = AtomicBoolean(false)
        @Volatile var writing = false
        override fun write(buf: ByteArray, off: Int, len: Int): Int {
            if (released.get()) { writeAfterRelease.set(true); throw IllegalStateException("Unable to retrieve AudioTrack pointer for write()") }
            writing = true
            inWrite.countDown()
            try { stopped.await() } finally { writing = false }
            return -1
        }
        override fun stop() { stopped.countDown() }
        override fun release() { writingAtRelease.set(writing); released.set(true) }
    }

    @Test fun closeDuringBlockedWriteReleasesOnlyAfterTheWriterStopped() {
        val sink = BlockingSink()
        val errors = AtomicReference<Throwable?>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors.set(e) }
        try {
            val feeder = PcmFeeder(pcm(), 0, sink)
            assertTrue(sink.inWrite.await(5, TimeUnit.SECONDS))
            feeder.close()
            assertTrue(sink.released.get())
            assertFalse("released while a write was running", sink.writingAtRelease.get())
            Thread.sleep(200)
            assertFalse(sink.writeAfterRelease.get())
            assertNull(errors.get())
        } finally { Thread.setDefaultUncaughtExceptionHandler(old) }
    }

    @Test fun anExceptionFromTheSinkDoesNotEscapeTheThread() {
        val errors = AtomicReference<Throwable?>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors.set(e) }
        val thrown = CountDownLatch(1)
        try {
            val feeder = PcmFeeder(pcm(), 0, object : PcmSink {
                override fun write(buf: ByteArray, off: Int, len: Int): Int { thrown.countDown(); throw IllegalStateException("released") }
                override fun stop() {}
                override fun release() {}
            })
            assertTrue(thrown.await(5, TimeUnit.SECONDS))
            feeder.close()
            assertNull(errors.get())
        } finally { Thread.setDefaultUncaughtExceptionHandler(old) }
    }
}
