/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class BackgroundPriorityTest {
    @Smoke @Test
    fun neuralVoiceWorkRunsAtBackgroundPriorityAndHandsThePriorityBack() {
        val tid = Process.myTid()
        val before = Process.getThreadPriority(tid)
        val during = NeuralVoice.inBackground { Process.getThreadPriority(tid) }
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, during)
        assertEquals(before, Process.getThreadPriority(tid))
    }
}
