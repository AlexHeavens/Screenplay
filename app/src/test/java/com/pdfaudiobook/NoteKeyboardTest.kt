/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteKeyboardTest {
    @Test fun notesStartSentencesWithACapital() {
        assertEquals(KeyboardCapitalization.Sentences, NoteKeyboardOptions.capitalization)
    }

    @Test fun doneStillSavesTheNote() {
        assertEquals(ImeAction.Done, NoteKeyboardOptions.imeAction)
    }
}
