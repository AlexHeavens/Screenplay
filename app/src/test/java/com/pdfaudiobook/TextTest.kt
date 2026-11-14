/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {
    @Test fun mergesParagraphCutByPageBreak() {
        assertEquals(listOf("The cat sat on the mat.", "Next one."),
            Text.mergeAcrossPages(listOf("The cat sat on", "the mat.", "Next one.")))
    }

    @Test fun keepsParagraphsThatEndInAFullStop() {
        assertEquals(listOf("One.", "two starts lower."), Text.mergeAcrossPages(listOf("One.", "two starts lower.")))
    }

    @Test fun splitsSentences() {
        assertEquals(listOf("Hello there.", "How are you?"), Text.sentences(listOf("Hello there. How are you?")))
    }

    @Test fun cutsOverlongSentences() {
        val long = List(100) { "word" }.joinToString(" ")
        val out = Text.sentences(listOf(long), max = 100)
        assert(out.all { it.length <= 100 })
        assertEquals(long, out.joinToString(" "))
    }

    @Test fun indexAtFindsCurrentSentence() {
        val s = listOf(Segment(0, 1000, "a"), Segment(1000, 2000, "b"))
        assertEquals(0, Timeline.indexAt(s, 999))
        assertEquals(1, Timeline.indexAt(s, 1000))
        assertEquals(1, Timeline.indexAt(s, 5000))
    }

    @Test fun fixesThePronounIOcrReadsAsT() {
        assertEquals(
            listOf("I am of the school that thinks so.", "And I have seen it, I'm sure, and I think so."),
            Text.clean(listOf("T am of the school that thinks so.", "And T have seen it, T'm sure, and T think so.")),
        )
    }

    @Test fun keepsALetterTThatIsNotThePronoun() {
        val blocks = listOf("The Model T was cheap.", "A T cell has a receptor.", "T. S. Eliot wrote it.")
        assertEquals(blocks, Text.clean(blocks))
    }

    @Test fun dropsPageNumbersAndStrayBlocks() {
        assertEquals(listOf("Real text here."), Text.clean(listOf("9", "xiv", "F", "I922", "Real text here.")))
    }

    @Test fun stripsPageNumberGluedToTheStartOfAContinuation() {
        assertEquals(listOf("secrate this ground."), Text.clean(listOf("56 secrate this ground.")))
        assertEquals(listOf("1863 was the year."), Text.clean(listOf("1863 was the year.")))
    }

    @Test fun dropsRepeatedShortBlocks() {
        assertEquals(listOf("THE", "GETTYSBURG ADDRESS"), Text.clean(listOf("THE", "GETTYSBURG ADDRESS", "THE", "GETTYSBURG ADDRESS")))
    }

    @Test fun rejoinsAWordHyphenatedAcrossPages() {
        assertEquals(listOf("we cannot consecrate this."), Text.mergeAcrossPages(listOf("we cannot con-", "secrate this.")))
    }

    @Test
    fun countsWordsSeparatedByAnyWhitespace() {
        assertEquals(4, Text.wordCount("  One two\nthree\tfour "))
        assertEquals(0, Text.wordCount("   "))
    }
}

class CleanupTest {
    private val prose = "This is the first real paragraph of the book, long enough to be clearly prose and not a heading or a listing of pages. It goes on and on, as real books do, for quite some time."

    @Test fun trimsToFirstHeadingFollowedByProse() {
        val p = listOf("A Book", "ISBN 978-0-00-000000-0", "Contents", "Preface 7 Chapter 1 9", "Preface", prose, "More.")
        assertEquals(listOf("Preface", prose, "More."), Text.trimFront(p))
    }

    @Test fun skipsAContentsEntryWithNoProseAfterIt() {
        val p = listOf("Preface", "Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 1", prose)
        assertEquals(listOf("Chapter 1", prose), Text.trimFront(p))
    }

    @Test fun fallsBackToTheImprintBlock() {
        val p = listOf("Title", "All rights reserved. ISBN 123", "It begins here.")
        assertEquals(listOf("It begins here."), Text.trimFront(p))
    }

    @Test fun leavesTextWithNoFrontMatterAlone() {
        assertEquals(listOf("Just text."), Text.trimFront(listOf("Just text.")))
    }

    @Test fun mergesAfterAbbreviationAndBeforeLowercase() {
        assertEquals(listOf("Dr. Smith went home.", "He said so and left."),
            Text.mergeFragments(listOf("Dr.", "Smith went home.", "He said so", "and left.")))
    }

    @Test fun appliesOnlyCloseSuggestionsToLowercaseWords() {
        val text = "The goverment of Tiwan is large."
        val fixes = listOf(Fix(4, 9, listOf("government")), Fix(17, 5, listOf("Taiwan")))
        assertEquals("The government of Tiwan is large.", Spelling.apply(text, fixes))
        assertEquals("cat", Spelling.apply("cat", listOf(Fix(0, 3, listOf("elephant")))))
    }

    @Test fun findsChapters() {
        val s = listOf(Segment(0, 1, "Preface"), Segment(1, 2, "Text here."), Segment(2, 3, "Chapter 2 Growth"))
        assertEquals(listOf(0, 2), Chapters.find(s).map { it.index })
        assertEquals("Chapter 2 Growth", Chapters.at(Chapters.find(s), 2)?.title)
    }

    @Test fun findsChaptersFromContents() {
        val s = listOf("CONTENTS", "The Harbour Office", "The Tides of Rapid Growth", "A long body sentence follows here, with a comma, and ends.",
            "The Tides of Rapid Growth ONE Observing the economy.", "The Harbour Office").mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) }
        val c = Chapters.find(s)
        assertEquals(listOf(4, 5), c.map { it.index })
        assertEquals("The Tides of Rapid Growth", c[0].title)
    }

    // The short lines below are from the OCR of a scanned history book.
    @Test fun dropsNumericLinesButKeepsChapterHeadings() {
        val p = listOf(prose, "19", "(1975 = 100)", "\"", "Chapter 12", prose)
        assertEquals(listOf(prose, "Chapter 12", prose), Text.dropNonProse(p))
    }

    @Test fun dropsRunsOfShortUnpunctuatedLines() {
        val p = listOf(prose, "ABBREVIATIONS", "AML", "Antimonopoly Law", "Board of Trade", prose, "The Harbour Lights", prose)
        assertEquals(listOf(prose, prose, "The Harbour Lights", prose), Text.dropNonProse(p))
    }

    @Test fun dropsTableCells() {
        val p = listOf(prose, "TABLE 1", "Manulacluring induslries", "Pelroleum", "Mining", "Year", prose)
        assertEquals(listOf(prose, prose), Text.dropNonProse(p))
    }

    // A contents page and abbreviation list that follow the preface, as OCR read them.
    @Test fun dropsContentsAndAbbreviationsWithPageNumbersLongEntriesAndQuestions() {
        val p = listOf(
            prose, "Berkeley", "C.J. The Harbour \"Lights\"", "The Tides of Rapid Growth Quiet Guidance",
            "APPENDIXES", "Distant Ports", "A Coastal Model?", "BỊBLIOGRAPHY",
            "A. The Keepers and Wardens of the Coastal Lights, 1925-1975, 327.",
            "B. Internal Organization of the Lamp Office, Selected Dates, 1925-1973, 332.",
            "C. The Careers of Wardens Ruiz and Okoye, 339.",
            "Chart Planning Board Tide Deliberation Agency Lamp Planning Agency",
            "Harbour Trade Association Federation of Pilot Guilds Coastal Democratic Party",
            "Supreme Commander of the Fleet Southern Harbour Railroad",
            "Temporary Lamp Rationality Bureau Temporary Materials Coordination Bureau",
            prose,
        )
        assertEquals(listOf(prose, prose), Text.dropNonProse(p))
    }

    // OCR joins the entries of a contents page into one paragraph, which a page break can cut mid-entry.
    @Test fun dropsContentsEntriesJoinedIntoOneParagraphCutAtThePageEnd() {
        val entries = "Percepts versus abstract concepts, 53. Influence of the latter on belief, 54. Kant's theological Ideas, 55. " +
            "The feeling of unreality, 63. Sense"
        val p = listOf(prose, "LECTURE III", "THE REALITY OF THE UNSEEN", entries, "PAGE", prose)
        assertEquals(listOf(prose, prose), Text.dropNonProse(p))
    }

    @Test fun keepsProseThatCitesAFewPages() {
        val cites = "He returns to this point on page 12, 40. and again much later, where the argument is finally settled at length."
        val p = listOf(prose, cites, prose)
        assertEquals(p, Text.dropNonProse(p))
    }

    @Test fun keepsShortSentencesAndParagraphsEndingInAYearAmongHeadings() {
        val year = "The ministry kept this structure, with small changes, until it was reorganized in 1973."
        val p = listOf(prose, "THE HARBOUR OFFICE", "I do not regret this.", "Harbour Policy", prose,
            "APPENDIXES", "Distant Ports", year, "Berkeley", "Antimonopoly Law", prose)
        assertEquals(p, Text.dropNonProse(p))
    }

    @Test fun keepsALongChapterSubtitleAfterARunningHead() {
        val subtitle = "Of the Union and Internal Prosperity of the Roman Empire, in the Age of the Antonines"
        val p = listOf(prose, "THE DECLINE AND FALL", "CHAPTER II", subtitle, prose)
        assertEquals(p, Text.dropNonProse(p))
    }

    @Test fun rejoinsASplitFirstLetter() {
        assertEquals(listOf("BY COMMON agreement among the sailors, the light first appeared."),
            Text.dropNonProse(listOf("B:", "Y COMMON agreement among the sailors, the light first appeared.")))
        assertEquals(listOf("I cannot prove that a particular harbour would not have grown."),
            Text.dropNonProse(listOf("G$", "I cannot prove that a particular harbour would not have grown.")))
    }

    @Test fun stripsFootnoteNumbersGluedToPunctuation() {
        assertEquals("Growth was fast. Costs rose.", Text.dropNonProse(listOf("Growth was fast.12 Costs rose.")).single())
        assertEquals("Costs rose by 3, 25 and 40 percent.", Text.dropNonProse(listOf("Costs rose by 3, 25 and 40 percent.")).single())
    }

    @Test fun stripsFootnoteSymbolsAfterATitleInCapitals() {
        assertEquals("PRODUCTION OF SOUND BY RADIANT ENERGY.", Text.dropNonProse(listOf("PRODUCTION OF SOUND BY RADIANT ENERGY.*")).single())
        assertEquals("Results came in. The tone was loud.", Text.dropNonProse(listOf("Results came in.‡ The tone was loud.")).single())
        val name = "The output of the works at PLANT B.2 rose every year until the war began."
        assertEquals(name, Text.dropNonProse(listOf(name)).single())
    }

    @Test fun stripsFootnoteNumbersAfterClosingQuotes() {
        val paragraphs = listOf(
            "Others call it \"shared lanterns,\"7 \"borrowed lanterns,\"8 or \"Port Ltd.\"19 My doubts are many.",
            "Much cargo is left \"unaccounted.\"15 If it can be shown that the tide mattered, the story changes.",
            "The two were \"more alike in 1910 than in 1970.\"25 The Port Board and its predecessors were not.",
            "The share fell to a quarter \"(26 percent).\"6 This shift was deliberate.",
            "He called it \"'something we built ourselves.'\"0 Marta feels otherwise.",
            "Growth was fast.* Costs rose too. Prices fell.*",
            "He is no longer subject to death.'\" 2 In the vision of God, it is \"found,\" 4 \"Ich bin so gross.\"",
        )
        assertEquals(listOf(
            "Others call it \"shared lanterns,\" \"borrowed lanterns,\" or \"Port Ltd.\"", "My doubts are many.",
            "Much cargo is left \"unaccounted.\"", "If it can be shown that the tide mattered, the story changes.",
            "The two were \"more alike in 1910 than in 1970.\"", "The Port Board and its predecessors were not.",
            "The share fell to a quarter \"(26 percent).\"", "This shift was deliberate.",
            "He called it \"'something we built ourselves.'\"", "Marta feels otherwise.",
            "Growth was fast.", "Costs rose too.", "Prices fell.",
            "He is no longer subject to death.'\"", "In the vision of God, it is \"found,\" \"Ich bin so gross.\"",
        ), Text.prepare(paragraphs, Options(mergeFragments = false)))
    }

    @Test fun keepsRealNumbersAfterQuotesAndDecimals() {
        val paragraphs = listOf("He said \"three or 4,\" 5 times.", "Growth averaged 3.25 percent, then \"9.\" 4 years later it fell.")
        assertEquals(listOf("He said \"three or 4,\" 5 times.", "Growth averaged 3.25 percent, then \"9.\" 4 years later it fell."),
            Text.prepare(paragraphs, Options(mergeFragments = false)))
    }

    @Test fun cutsAtBackMatterInTheSecondHalfOnly() {
        val p = listOf("CONTENTS", "NOTES", "INDEX", prose, prose, prose, "APPENDIXES", "Appendix text.", "NOTES", "1. Ibid.")
        assertEquals(p.take(6), Text.trimBack(p))
    }

    @Test fun usesSavedContentsWhenTheTranscriptHasNone() {
        val s = listOf(prose, "The Harbour Office", prose).mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) }
        assertEquals(listOf(1), Chapters.find(s, listOf("The Harbour Office")).map { it.index })
    }

    // The lines below are the OCR of a scanned history book whose contents page prints each chapter's number and title
    // again where the chapter opens. Layout keeps both; clean dropped the repeats, so no chapter heading reached the transcript.
    private val contentsThenOpenings = listOf(
        "PREFACE", prose, "CONTENTS", "ONE", "TWO", "THREE", "The Harbour \"Lights\"", "The Harbour Office", "The Rise of the Salt Trade",
        "THE LIGHTHOUSE AND THE HARBOUR", "ONE", "The Harbour \"Lights\"", prose, "TWO", "W:", "The Harbour Office", prose,
        "THREE", "The Rise of the Salt Trade", prose,
    )

    @Test fun keepsAChapterHeadingThatTheContentsPageAlreadyPrinted() {
        val p = Text.clean(contentsThenOpenings)
        assertEquals(listOf("ONE", "The Harbour \"Lights\""), p.subList(p.indexOf("THE LIGHTHOUSE AND THE HARBOUR") + 1, p.indexOf("THE LIGHTHOUSE AND THE HARBOUR") + 3))
        assertEquals(2, p.count { it == "ONE" })
    }

    @Test fun findsChaptersFromTheirNumbersAndTitles() {
        val body = Text.prepare(contentsThenOpenings, Options())
        val c = Chapters.find(body.mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) })
        assertEquals(listOf("PREFACE", "ONE The Harbour \"Lights\"", "TWO The Harbour Office", "THREE The Rise of the Salt Trade"), c.map { it.title })
    }

    @Test fun findsALectureHeadingAndItsTitle() {
        val s = listOf(prose, "LECTURE I", "RELIGION AND NEUROLOGY", prose).mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) }
        assertEquals(listOf("LECTURE I RELIGION AND NEUROLOGY"), Chapters.find(s).map { it.title })
    }

    @Test fun aRunOfChapterNumbersIsAContentsListNotChapters() {
        val s = listOf(prose, "ONE", "TWO", "THREE", prose).mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) }
        assertEquals(emptyList<Chapter>(), Chapters.find(s))
    }

    @Test fun stripsAFootnoteNumberAfterTheQuoteEndingAParagraph() {
        assertEquals(listOf(prose + " and \"Port Ltd.\""), Text.dropNonProse(listOf(prose + " and \"Port Ltd.\" 19")))
    }

    @Test fun keepsANumberAfterAQuoteWhenTextFollows() {
        val p = prose + " in \"Port Ltd.\" 19 firms agreed."
        assertEquals(listOf(p), Text.dropNonProse(listOf(p)))
    }

    @Test fun stripsAFootnoteNumberOcrReadsAsASecondQuote() {
        assertEquals(listOf(prose + " the budget.\""), Text.dropNonProse(listOf(prose + " the budget.\"\"")))
    }

    @Test fun anEstimatedChapterWithAGarbledHeadingIsMarkedAtItsPagesFirstParagraph() {
        val s = listOf(prose, "0NE Tbe Opening", "BY COMMON agreement among the sailors, the light first appeared to them in 1962.", prose, "TWO", "The Second", prose)
            .mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) }
        val estimates = listOf(
            ChapterEstimate("ONE The Opening", 3, 19, listOf("0NE Tbe Opening", "BY COMMON agreement among the sailors")),
            ChapterEstimate("TWO The Second", 35, 51, listOf("TWO", "The Second")),
        )
        // Without the estimate the garbled heading is missed; with it the chapter starts at the page's first paragraph, and the clean one keeps its text heading.
        assertEquals(emptyList<Chapter>(), Chapters.find(s.take(4)))
        val c = Chapters.find(s, estimates = estimates)
        assertEquals(listOf(1 to "ONE The Opening", 4 to "TWO The Second"), c.map { it.index to it.title })
    }

    @Test fun anEstimatedChapterWhoseAnchorIsNotInTheTextIsLeftOut() {
        val s = listOf(prose, prose).mapIndexed { i, x -> Segment(i * 10L, i * 10L + 9, x) }
        assertEquals(emptyList<Chapter>(), Chapters.find(s, estimates = listOf(ChapterEstimate("ONE X", 3, 19, listOf("Never read")))))
    }
}
