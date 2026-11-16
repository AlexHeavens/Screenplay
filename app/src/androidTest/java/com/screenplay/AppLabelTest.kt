/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLabelTest {
    @Smoke
    @Test
    fun launcherLabelIsScreenplay() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Screenplay", ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString())
        assertEquals("com.screenplay", ctx.packageName)
    }
}
