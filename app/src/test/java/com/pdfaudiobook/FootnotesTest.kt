/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages as OCR returns them: a list of paragraphs, and for geometry a list of blocks with line boxes. */
class FootnotesTest {
    private val citing = "Louis XII, the late King of France, made [3] a great mistake in Italy, for he allowed the Church to grow strong."
    private val louis = "[3] Louis XII. entered Italy in 1499 at the invitation of the Venetians."

    @Test fun takesAFootnoteOutOfThePageAndMatchesItToTheParagraphThatCitesIt() {
        val page = Footnotes.split(listOf("An earlier paragraph about Milan.", citing, louis))
        assertEquals(
            listOf("An earlier paragraph about Milan.", "Louis XII, the late King of France, made a great mistake in Italy, for he allowed the Church to grow strong."),
            page.paragraphs,
        )
        assertEquals(listOf(Footnote("Louis XII, the late King of France, made", louis)), page.footnotes)
    }

    @Test fun placesTheFootnoteUnderTheCitingParagraphAmongTheWholeTranscript() {
        val page = Footnotes.split(listOf("An earlier paragraph about Milan.", citing, louis))
        val transcript = listOf("A paragraph from an earlier page.") + page.paragraphs + listOf("A paragraph from a later page.")
        assertEquals(mapOf(2 to listOf(louis)), Footnotes.place(page.footnotes, transcript))
    }

    @Test fun aPageWithoutFootnotesIsUnchanged() {
        val paragraphs = listOf("The prince must be feared.", "He should avoid being hated.")
        assertEquals(PageText(paragraphs), Footnotes.split(paragraphs))
    }

    @Test fun aNumberedListIsNotAFootnote() {
        val paragraphs = listOf("To make the dough, follow these steps.", "1. Mix the flour and water in a bowl.", "2. Knead for ten minutes.")
        assertEquals(PageText(paragraphs), Footnotes.split(paragraphs))
    }

    @Test fun aFigureCaptionIsNotAFootnote() {
        val paragraphs = listOf("The harbour was dredged in 1851 to admit larger ships.", "Figure 3. The harbour mouth at low tide.")
        assertEquals(PageText(paragraphs), Footnotes.split(paragraphs))
    }

    @Test fun aNumberedItemIsANoteWhenTheBodyCitesItsNumber() {
        val page = Footnotes.split(listOf("The harbour was dredged in 1851.2 It admitted larger ships.", "2 Report of the harbour board, p. 14."))
        assertEquals(listOf("The harbour was dredged in 1851. It admitted larger ships."), page.paragraphs)
        assertEquals(listOf("2 Report of the harbour board, p. 14."), page.footnotes.map { it.text })
        assertEquals("The harbour was dredged in 1851.", page.footnotes.single().anchor)
    }

    @Test fun aSymbolNoteNeedsItsSymbolInTheBody() {
        val cited = Footnotes.split(listOf("Energy.* is the capacity to do work, as the lecture said.", "* Delivered before the Society, May 1881."))
        assertEquals(listOf("* Delivered before the Society, May 1881."), cited.footnotes.map { it.text })
        val bullet = listOf("Things to bring on the walk, as the guide said.", "* A flask of tea and a sandwich.")
        assertEquals(PageText(bullet), Footnotes.split(bullet))
    }

    @Test fun anUnmatchedFootnoteStaysWithTheLastParagraphOnThePage() {
        val page = Footnotes.split(listOf("The first paragraph of the page.", "The last paragraph of the page ends here.", "[7] Ibid., p. 51."))
        assertEquals(2, page.paragraphs.size)
        assertEquals(listOf(Footnote("last paragraph of the page ends here.", "[7] Ibid., p. 51.")), page.footnotes)
    }

    @Test fun twoFootnotesOnOnePageAreEachMatched() {
        val first = "Cesare Borgia [1] had won the Romagna by arms, and he kept it by cruelty."
        val second = "Remirro de Orco, [2] a cruel and active man, was given full power."
        val page = Footnotes.split(listOf(first, second, "[1] Duke Valentino.", "[2] Governor of the Romagna."))
        assertEquals(listOf("Cesare Borgia had won the Romagna by arms, and he kept it by cruelty.", "Remirro de Orco, a cruel and active man, was given full power."), page.paragraphs)
        assertEquals(listOf("Cesare Borgia", "Remirro de Orco,"), page.footnotes.map { it.anchor })
        assertEquals(mapOf(0 to listOf("[1] Duke Valentino."), 1 to listOf("[2] Governor of the Romagna.")), Footnotes.place(page.footnotes, page.paragraphs))
    }

    @Test fun aPageOfNothingButNotesIsLeftAsText() {
        val paragraphs = listOf("[1] Smith, J., A History of Italy.", "[2] Jones, K., Florence.")
        assertEquals(PageText(paragraphs), Footnotes.split(paragraphs))
    }

    @Test fun aFootnoteGoesWithItsParagraphWhenAnEarlierOneIsDropped() {
        val footnotes = listOf(Footnote("never to be found", "[1] First."), Footnote("the second paragraph", "[2] Second."))
        assertEquals(
            mapOf(0 to listOf("[1] First."), 1 to listOf("[2] Second.")),
            Footnotes.place(footnotes, listOf("An opening.", "This is the second paragraph of the book.")),
        )
    }

    @Test fun anotherFootnoteIsPlacedAfterTheFirst() {
        val footnotes = listOf(Footnote("a repeated phrase", "[1] One."), Footnote("a repeated phrase", "[2] Two."))
        val texts = listOf("a repeated phrase here", "and a repeated phrase again")
        assertEquals(mapOf(0 to listOf("[1] One.", "[2] Two.")), Footnotes.place(footnotes, texts))
    }

    // Geometry: an ML Kit page with small-type note blocks at the foot.
    private fun line(top: Int, height: Int, text: String) = OcrLine(top, top + height, text)

    private val bodyBlocks = listOf(
        listOf(line(300, 44, "Louis XII, the late King of France, made [3] a great"), line(348, 44, "mistake in Italy, for he allowed the Church to grow strong."), line(396, 44, "He lost Milan and then Naples soon after that.")),
    )

    @Test fun geometryFindsASmallTypeNoteAtTheFootOfThePageWhateverItSays() {
        val page = bodyBlocks + listOf(listOf(line(2000, 30, "[3] Louis XII. entered Italy in 1499"), line(2034, 30, "at the invitation of the Venetians.")))
        val read = Layout.page(page, 2400, footnotes = true)
        assertEquals(listOf("Louis XII, the late King of France, made a great mistake in Italy, for he allowed the Church to grow strong. He lost Milan and then Naples soon after that."), read.paragraphs)
        assertEquals(listOf("[3] Louis XII. entered Italy in 1499 at the invitation of the Venetians."), read.footnotes.map { it.text })
    }

    @Test fun withTheOptionOffAPageReadsAsItAlwaysDid() {
        val page = bodyBlocks + listOf(listOf(line(2000, 30, "[3] Louis XII. entered Italy in 1499"), line(2034, 30, "at the invitation of the Venetians.")))
        val read = Layout.page(page, 2400, footnotes = false)
        assertEquals(Layout.paragraphs(page, 2400), read.paragraphs)
        assertTrue(read.footnotes.isEmpty())
    }

    @Test fun aNoteInBodySizedTypeIsFoundByItsLabel() {
        val page = bodyBlocks + listOf(listOf(line(1500, 44, "[3] Louis XII. entered Italy in 1499 at the invitation")))
        val read = Layout.page(page, 2400, footnotes = true)
        assertEquals(1, read.paragraphs.size)
        assertEquals(listOf("[3] Louis XII. entered Italy in 1499 at the invitation"), read.footnotes.map { it.text })
    }
}
