/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.util.Log
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Logs each test's wall time to logcat (tag `TestTiming`), so the slowest tests show up in `adb logcat -d -s TestTiming`. */
class TestTimingListener : RunListener() {
    private var startNanos = 0L

    override fun testStarted(description: Description) {
        startNanos = System.nanoTime()
    }

    override fun testFinished(description: Description) {
        Log.i("TestTiming", "${description.className.substringAfterLast('.')}#${description.methodName}\t${(System.nanoTime() - startNanos) / 1_000_000} ms")
    }
}
