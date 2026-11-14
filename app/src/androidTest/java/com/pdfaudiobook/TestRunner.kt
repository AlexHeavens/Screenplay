/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.Manifest
import androidx.test.runner.AndroidJUnitRunner

/** Grants the notification permission up front, so its system dialog never covers the app in a test. */
class TestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        super.onStart()
    }
}
