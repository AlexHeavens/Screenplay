/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The launcher shows the open-book adaptive icon, with a layer for themed icons. */
@RunWith(AndroidJUnit4::class)
class AppIconTest {
    @Smoke
    @Test
    fun launcherIconIsAnAdaptiveIconWithMonochromeLayer() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertNotEquals("manifest declares no icon", 0, info.icon)
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue("launcher icon is ${icon::class.java.simpleName}", icon is AdaptiveIconDrawable)
        assertNotNull(
            "no monochrome layer for themed icons",
            (icon as AdaptiveIconDrawable).monochrome,
        )
    }
}
