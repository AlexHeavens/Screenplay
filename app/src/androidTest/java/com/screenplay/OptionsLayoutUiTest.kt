/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In the options sheet an option's text never touches its switch: the text column ends at least [OptionGap] before the switch begins. */
@RunWith(AndroidJUnit4::class)
class OptionsLayoutUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    @After
    fun restore() {
        scenario?.close()
        shell("settings put system font_scale 1.0")
    }

    private fun assertGaps(fontScale: String) {
        shell("settings put system font_scale $fontScale")
        Options(spell = false, voice = Voice.PHONE).save(ctx)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        val gap = with(androidx.compose.ui.unit.Density(ctx.resources.displayMetrics.density)) { OptionGap.toPx() }
        for (tag in listOf("opt-spell", "opt-trim", "opt-merge", "opt-clean", "opt-footnotes", "opt-contents")) {
            val switch = compose.onNodeWithTag("$tag-switch", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            for (part in listOf("label", "hint")) {
                val text = compose.onAllNodesWithTag("$tag-$part", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: continue
                assertTrue("$tag-$part ends at ${text.right}, switch starts at ${switch.left}, need gap $gap (font scale $fontScale)", text.right + gap <= switch.left + 0.5f)
            }
        }
    }

    @Test fun textStopsShortOfTheSwitchAtNormalFontScale() = assertGaps("1.0")

    @Test fun textStopsShortOfTheSwitchAtLargeFontScale() = assertGaps("2.0")
}
