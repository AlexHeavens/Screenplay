/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** The theme choice in the options pane: Light, Dark or Follow system, applied at once and kept. */
@RunWith(AndroidJUnit4::class)
class ThemeModeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shell(cmd: String) {
        FileInputStream(inst.uiAutomation.executeShellCommand(cmd).fileDescriptor).use { it.readBytes() }
    }

    @Before fun setUp() {
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        shell("cmd uimode night no")
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
    }

    @After fun tearDown() {
        scenario?.close()
        shell("cmd uimode night auto")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    private fun expect(scheme: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("theme-$scheme").fetchSemanticsNodes().isNotEmpty()
    }

    private fun pick(mode: ThemeMode) {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("theme-${mode.name}").performClick()
    }

    @Smoke @Test
    fun optionsPaneSwitchesBetweenLightDarkAndFollowSystem() {
        expect("light")
        pick(ThemeMode.DARK)
        expect("dark")
        pick(ThemeMode.LIGHT)
        expect("light")
        // Follow system tracks the device in both directions.
        pick(ThemeMode.SYSTEM)
        shell("cmd uimode night yes")
        expect("dark")
        shell("cmd uimode night no")
        expect("light")
    }

    /** The chosen chip takes its fill from the scheme in force; the stock chip colour is a purple that ignores the theme. */
    @Smoke @Test
    fun themeChipsFollowTheActiveScheme() {
        for ((mode, scheme) in listOf(ThemeMode.DARK to DarkColors, ThemeMode.LIGHT to LightColors)) {
            pick(mode)
            expect(mode.name.lowercase())
            // A taller font on some devices (redroid) pushes the chips below the screen edge, where the capture is blank: scroll them into view.
            runCatching { compose.onNodeWithTag("theme-${mode.name}").performScrollTo() }
            compose.waitForIdle()
            val bmp = compose.onNodeWithTag("theme-${mode.name}").captureToImage().asAndroidBitmap()
            // Just inside the left edge, at mid height: fill, clear of the label and the rounded corners.
            val rgb = bmp.getPixel((8 * ctx.resources.displayMetrics.density).toInt(), bmp.height / 2) and 0xFFFFFF
            // Software rendering (redroid) lands a few units off the exact colour per channel, so compare with a small tolerance.
            val want = scheme.primaryContainer.toArgb() and 0xFFFFFF
            for (shift in listOf(16, 8, 0)) {
                val diff = Math.abs(((want shr shift) and 0xFF) - ((rgb shr shift) and 0xFF))
                assertTrue("${mode.name} chip fill expected ${"%06x".format(want)} but was ${"%06x".format(rgb)}", diff <= 6)
            }
        }
    }

    @Test
    fun choiceSurvivesRecreatingTheActivity() {
        pick(ThemeMode.DARK)
        expect("dark")
        assertEquals(ThemeMode.DARK, Options.load(ctx).theme)
        scenario!!.recreate()
        expect("dark")
        scenario!!.close()
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        expect("dark")
    }
}
