/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The library screen's title is "Screenplay Audiobooks", not the app's file-type name. */
@RunWith(AndroidJUnit4::class)
class LibraryTitleTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Smoke
    @Test
    fun libraryScreenTitleIsAudiobooks() {
        compose.onNodeWithText("Screenplay Audiobooks").assertIsDisplayed()
    }

    @Smoke
    @Test
    fun libraryTitleShowsTheOpenBookIcon() {
        compose.onNodeWithContentDescription("Open book").assertIsDisplayed()
    }
}
