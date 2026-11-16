/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import java.io.File

/**
 * On the book screen the row of buttons (Play through Sleep) and the progress line beneath it
 * ("0:03 / 1:00  4% played") sit on the screen's centre line, the progress below the buttons.
 */
@RunWith(AndroidJUnit4::class)
class ControlsCentredTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Options(spell = false).save(ctx)
        Engines.synthesizer = { FakeSynthesizer(msPerWord = 2500) }
    }

    @After
    fun close() {
        scenario?.close()
        MediaControl.close()
    }

    @Smoke @Test
    fun theButtonsAndTheProgressUnderThemAreCentred() {
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf"),
        )
        compose.waitUntil(120_000) { compose.onAllNodes(hasContentDescription("Listened")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-${Fixtures.TITLE}").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "play")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("play").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("played").fetchSemanticsNodes().isNotEmpty() }

        val screen = compose.onRoot().getBoundsInRoot()
        val controls = compose.onNodeWithTag("controls").getBoundsInRoot()
        val middle = (screen.left + screen.right) / 2
        fun mid(b: androidx.compose.ui.unit.DpRect) = (b.left + b.right) / 2
        assertEquals("button row midpoint", middle.value, mid(controls).value, 1f)
        val progress = compose.onNodeWithTag("progress-line").getBoundsInRoot()
        assertEquals("progress line midpoint", middle.value, mid(progress).value, 1f)
        assertTrue("progress below the buttons", progress.top >= controls.bottom)
    }
}
