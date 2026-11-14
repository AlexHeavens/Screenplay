/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun lightAndDarkDiffer() {
        assertNotEquals(LightColors.primary, DarkColors.primary)
        assertNotEquals(LightColors.surface, DarkColors.surface)
        assertTrue("light surface is lighter", LightColors.surface.luminance() > 0.8f)
        assertTrue("dark surface is dark", DarkColors.surface.luminance() < 0.05f)
    }

    @Test
    fun schemeTextIsReadable() {
        for ((name, c) in listOf("light" to LightColors, "dark" to DarkColors)) {
            for ((label, fg, bg) in listOf(
                Triple("onSurface/surface", c.onSurface, c.surface),
                Triple("onBackground/background", c.onBackground, c.background),
                Triple("onPrimary/primary", c.onPrimary, c.primary),
                Triple("primary/surface", c.primary, c.surface),
                Triple("onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer),
                Triple("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant),
            )) assertTrue("$name $label contrast ${contrast(fg, bg)}", contrast(fg, bg) >= 4.5f)
        }
    }

    @Test
    fun progressGreysAreReadable() {
        for ((name, g, c) in listOf(Triple("light", LightGreys, LightColors), Triple("dark", DarkGreys, DarkColors))) {
            assertTrue("$name readLabel", contrast(g.readLabel, c.surface) >= 3f)
            assertTrue("$name speech", contrast(g.speech, c.surface) >= 3f)
            assertTrue("$name spokenText", contrast(g.spokenText, c.surface) >= 7f)
            assertTrue("$name spoken text stands apart from read label", contrast(g.spokenText, g.readLabel) >= 2f)
            assertTrue("$name read track is visible", contrast(g.read, c.surface) >= 1.2f)
        }
    }

    @Test
    fun themeChipsDrawFromTheSchemeAndAreReadable() {
        for ((name, c) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val p = chipPalette(c)
            assertEquals("$name fill", c.primaryContainer, p.selectedContainer)
            assertTrue("$name selected label", contrast(p.selectedLabel, p.selectedContainer) >= 4.5f)
            assertTrue("$name label", contrast(p.label, c.surface) >= 4.5f)
        }
    }

    @Test
    fun controlsShareOneEightDpRadius() {
        assertEquals(RoundedCornerShape(8.dp), ControlShape)
    }
}
