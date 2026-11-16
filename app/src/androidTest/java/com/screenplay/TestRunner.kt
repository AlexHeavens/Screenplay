/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.Manifest
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Grants the notification permission up front, so its system dialog never covers the app in a test. */
class TestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Adds the timing listener to any `-e listener` the caller passed.
        val args = arguments ?: Bundle()
        args.putString("listener", listOfNotNull(args.getString("listener"), TestTimingListener::class.java.name).joinToString(","))
        super.onCreate(args)
    }

    override fun onStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        super.onStart()
    }
}
