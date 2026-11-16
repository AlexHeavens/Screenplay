/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun everyHighlightColourKeepsTheTranscriptTextReadableInBothThemes() {
        val textLight = LightColors.onSurface
        val textDark = DarkColors.onSurface
        assertEquals(6, HighlightColor.entries.size)
        for (c in HighlightColor.entries) {
            // The pen is translucent: measure the text against the colour as it lands on each theme's own background.
            val light = contrast(textLight, c.pen(dark = false).compositeOver(LightColors.background))
            val dark = contrast(textDark, c.pen(dark = true).compositeOver(DarkColors.background))
            assertTrue("${c.name} light is ${"%.2f".format(light)}:1", light >= 4.5)
            assertTrue("${c.name} dark is ${"%.2f".format(dark)}:1", dark >= 4.5)
        }
    }

    @Test
    fun yellowIsTheDefaultAndFirstInTheWheel_orangeStaysAChoice() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.DEFAULT)
        assertEquals(0, HighlightColor.entries.indexOf(HighlightColor.YELLOW))
        assertTrue(HighlightColor.ORANGE in HighlightColor.entries)
    }

    @Test
    fun theWheelHasSixEqualSlicesClockwiseFromTheTop() {
        // Each slice's own centre direction lands in that slice; the hole and far outside pick nothing.
        for (k in 0..5) {
            val c = HighlightWheel.centreOf(k, 60f)
            assertEquals(k, HighlightWheel.sliceAt(c.x, c.y))
        }
        assertNull(HighlightWheel.sliceAt(5f, 5f))
        assertNull(HighlightWheel.sliceAt(0f, -400f))
    }

    private fun segs(vararg t: String) = t.mapIndexed { i, s -> Segment(i * 1000L, i * 1000L + 900, s) }

    @Test
    fun togglingHighlightsInTheDefaultYellowThenRemovesIt_andAColourChangesAnExistingOne() {
        val s = segs("a", "b", "c")
        var all = Highlights.toggle(emptyList(), emptyMap(), s, 1)
        assertEquals(listOf(HighlightColor.YELLOW), all.map { it.color })
        assertEquals(1, Highlights.place(all, s).keys.single())
        all = Highlights.toggle(all, Highlights.place(all, s), s, 1, HighlightColor.PINK)
        assertEquals(listOf(HighlightColor.PINK), all.map { it.color })
        all = Highlights.toggle(all, Highlights.place(all, s), s, 1)
        assertTrue(all.isEmpty())
    }

    @Test
    fun aHighlightFollowsItsParagraphWhenTheTranscriptShifts() {
        val s = segs("a", "b", "c")
        val all = Highlights.toggle(emptyList(), emptyMap(), s, 2)
        assertEquals(3, Highlights.place(all, segs("x", "a", "b", "c")).keys.single())
    }

    @Test
    fun theOpaquePaletteAlsoKeepsTheTextReadable() {
        // Guards the palette itself: the pen only lightens or darkens toward the background, it must not be what rescues a colour.
        for (c in HighlightColor.entries) {
            assertTrue(c.name, contrast(LightColors.onSurface, c.background(false)) >= 4.5)
            assertTrue(c.name, contrast(DarkColors.onSurface, c.background(true)) >= 4.5)
        }
    }

    @Test
    fun theHighlightIsTranslucent() {
        assertTrue(HighlightBands.ALPHA in 0.3f..0.8f)
        for (c in HighlightColor.entries) assertEquals(HighlightBands.ALPHA, c.pen(false).alpha, 0.001f)
    }

    // Three wrapped lines of 20 px, the last one short.
    private val lines = listOf(Rect(0f, 0f, 300f, 20f), Rect(0f, 20f, 296f, 40f), Rect(0f, 40f, 90f, 60f))

    @Test
    fun oneBandPerLineEachWithItsOwnExtent_aShortLastLineGetsAShortBand() {
        val bands = HighlightBands.of(lines, inset = 2f, bleed = 0f)
        assertEquals(3, bands.size)
        assertEquals(listOf(300f, 296f, 90f), bands.map { it.width })
        assertEquals(listOf(0f, 0f, 0f), bands.map { it.left })
    }

    @Test
    fun bandsOfAdjacentLinesDoNotTouch() {
        val bands = HighlightBands.of(lines, inset = 2f, bleed = 0f)
        for ((a, b) in bands.zipWithNext()) assertTrue("gap ${b.top - a.bottom}", b.top - a.bottom > 0f)
        assertEquals(16f, bands[0].height, 0.001f)
    }

    @Test
    fun theInsetNeverEatsALine_andBleedWidensEachSide() {
        val tight = HighlightBands.of(listOf(Rect(10f, 0f, 50f, 8f)), inset = 100f, bleed = 3f).single()
        assertEquals(4f, tight.height, 0.001f) // at most a quarter off each end
        assertEquals(7f, tight.left, 0.001f)
        assertEquals(53f, tight.right, 0.001f)
    }

    @Test
    fun anEmptyLineGetsNoBand() {
        assertEquals(2, HighlightBands.of(lines + Rect(0f, 60f, 0f, 80f), inset = 1f, bleed = 0f).size - 1)
    }
}
