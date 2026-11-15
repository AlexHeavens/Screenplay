/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A long title must not push the header's buttons off a narrow phone screen. */
@RunWith(AndroidJUnit4::class)
class BookHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Smoke
    @Test
    fun longTitleKeepsButtonsInViewAndEndsInEllipsis() {
        val width = 320.dp
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width)) {
                    BookTopBar(
                        title = "The Extraordinarily Long and Winding Title of a Book Whose Subtitle Goes On: A Complete History of Everything",
                        exportEnabled = true,
                        onBack = {}, onSearch = {}, onExport = {}, exportMenu = {},
                    )
                }
            }
        }
        for (tag in listOf("back", "export", "title")) compose.onNodeWithTag(tag).assertIsDisplayed()
        val bar = compose.onNodeWithTag("title").getBoundsInRoot()
        val export = compose.onNodeWithTag("export").getBoundsInRoot()
        assertTrue("share ends past ${width}: ${export.right}", export.right <= width)
        compose.onNodeWithTag("backup").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        assertTrue("title ${bar.right} overlaps export ${export.left}", bar.right <= export.left)
        // Two lines of the medium title style at most: the text is cut by an ellipsis, not left to overflow the bar.
        assertTrue("title ${bar.height} too tall", bar.height <= 48.dp)
    }
}
