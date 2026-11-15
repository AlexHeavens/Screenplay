/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
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
import java.util.zip.ZipInputStream

/**
 * Drives the real app with a fake speech engine; OCR, the AAC encoder, the player and the share sheet are real.
 *
 * Only the tests that assert the import itself run it on the fixture PDF (real OCR, about ten seconds each): the transcript
 * OCR produces, the engine an import speaks with, and a PDF with no text. Every other test opens a book from
 * [Fixtures.seedSpokenBook], which has the same files and real AAC audio, and checks screen behaviour: controls, swipes, notes,
 * chapters, speed, backup. Tests that never open a book (settings, voice picker) need no book at all.
 */
@RunWith(AndroidJUnit4::class)
class BookUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val title = Fixtures.TITLE

    @Before
    fun freshInstall() {
        File(ctx.filesDir, "books").deleteRecursively()
        Library.refresh(ctx)
        Engines.synthesizer = { FakeSynthesizer() }
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun launchWithPdf() {
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(Fixtures.pdf(ctx), "application/pdf")
        scenario = ActivityScenario.launch(intent)
    }

    /** [unmerged] reaches a note inside a clickable sentence, whose children Compose merges into one node. */
    private fun waitFor(timeout: Long = 120_000, what: String, unmerged: Boolean = false, matcher: SemanticsMatcher) {
        compose.waitUntil(timeout) {
            compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openImportedBook() {
        launchWithPdf()
        waitFor(what = "ready", matcher = hasContentDescription("Listened"))
        openShelfBook()
    }

    /** Opens a book made by [Fixtures.seedSpokenBook] in place of an import, so a test of screen behaviour does not wait for OCR. */
    private fun openSeededBook() {
        Fixtures.seedSpokenBook(ctx)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(what = "shelf", matcher = hasContentDescription("Listened"))
        openShelfBook()
    }

    private fun openShelfBook() {
        compose.onNodeWithTag("book-$title").performClick()
        waitFor(what = "transcript", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0"))
    }

    private fun waitForSearchCount(text: String) = waitFor(what = "search count $text", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "search-count").and(hasText(text)))

    /** "quick" is in two of the three seeded sentences: the search counts both, steps between them with wrap-around, and never starts the audio. */
    @Test
    fun searchStepsThroughMatchesAndWrapsAround() {
        openSeededBook()
        compose.onNodeWithTag("search").performClick()
        compose.onNodeWithTag("search-field").performTextInput("QUICK")
        waitForSearchCount("1 of 2")
        compose.onNodeWithTag("search-next").performClick()
        waitForSearchCount("2 of 2")
        compose.onNodeWithTag("search-next").performClick()
        waitForSearchCount("1 of 2")
        compose.onNodeWithTag("search-previous").performClick()
        waitForSearchCount("2 of 2")
        compose.onNodeWithTag("search-field").performImeAction()
        waitForSearchCount("1 of 2")
        // Highlight all is on by default: both sentences are marked. Off, only the current match is, and Next moves the mark.
        val marked = SemanticsMatcher.keyIsDefined(SearchMarksKey)
        compose.onNodeWithTag("sentence-0").assert(marked)
        compose.onNodeWithTag("sentence-2").assert(marked)
        compose.onNodeWithTag("search-highlight-all").performClick()
        waitFor(what = "only the current match marked", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-2").and(SemanticsMatcher.keyNotDefined(SearchMarksKey)))
        compose.onNodeWithTag("sentence-0").assert(marked)
        compose.onNodeWithTag("search-next").performClick()
        waitForSearchCount("2 of 2")
        waitFor(what = "mark moved", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0").and(SemanticsMatcher.keyNotDefined(SearchMarksKey)))
        compose.onNodeWithTag("sentence-2").assert(marked)
        compose.onNodeWithTag("search-highlight-all").performClick()
        waitFor(what = "all marked again", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-0").and(marked))
        compose.onNodeWithTag("play").assertTextEquals("Play")
        compose.onNodeWithTag("search-close").performClick()
        compose.onAllNodesWithTag("search-field").assertCountEquals(0)
        compose.onNodeWithTag("search").assertIsDisplayed()
    }

    /** No match says so and keeps the field; an empty query shows nothing; the query survives a rotation; Back closes the search and stays on the book. */
    @Test
    fun searchWithNoMatchEmptyQueryRotationAndBack() {
        openSeededBook()
        compose.onNodeWithTag("search").performClick()
        compose.onNodeWithTag("search-field").performTextInput("zebra")
        waitForSearchCount("1 of 1")
        compose.onNodeWithTag("search-field").performTextClearance()
        compose.onNodeWithTag("search-field").performTextInput("giraffe")
        waitForSearchCount("No matches")
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        scenario!!.recreate()
        compose.onNodeWithTag("search-field").assertTextContains("giraffe")
        waitForSearchCount("No matches")
        compose.onNodeWithTag("search-field").performTextClearance()
        waitFor(what = "empty counter", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "search-count").and(hasText("")))
        // With a keyboard on screen (redroid) the first Back only hides it, so hide it first and let Back close the search.
        runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
        androidx.test.espresso.Espresso.pressBack()
        compose.onAllNodesWithTag("search-field").assertCountEquals(0)
        compose.onNodeWithTag("title").assertIsDisplayed()
    }

    /** Play, speed and Notes share the theme chips' corner radius: a pixel on the arc of an 8dp corner is painted, on a pill's it is not. */
    @Smoke @Test
    fun playSpeedAndNotesShareTheControlRadius() {
        openSeededBook()
        val px = (2.6f * ctx.resources.displayMetrics.density).toInt()
        for (tag in listOf("play", "speed", "notes")) {
            val bmp = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            val alpha = android.graphics.Color.alpha(bmp.getPixel(px, px))
            assertTrue("$tag corner is not the 8dp radius (alpha $alpha at $px,$px)", alpha > 128)
        }
    }

    @Smoke @Test
    fun cogOpensSettingsAndSwitchShowsItsState() {
        getInstrumentationPrefsReset()
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("opt-spell").assertIsOff()
        compose.onNodeWithTag("opt-spell").performClick()
        compose.onNodeWithTag("opt-spell").assertIsOn()
        assertTrue(Options.load(ctx).spell)
        compose.onNodeWithTag("opt-spell").performClick()
        compose.onNodeWithTag("opt-spell").assertIsOff()
    }

    /** A fake that records which engine spoke, so a test can tell the neural lane from the phone's own. */
    private class Named(val name: String, val used: MutableSet<String>) : Synthesizer {
        override suspend fun speak(text: String, wav: File) { used += name; FakeSynthesizer().speak(text, wav) }
        override fun close() = Unit
    }

    private fun importAndCollectEngines(): Set<String> {
        val used = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        Engines.synthesizer = { Named("android", used) }
        Engines.neural = { _, voice -> Named(voice.name, used) }
        Engines.prepareNeural = { _, _, progress -> progress(1f) }
        launchWithPdf()
        waitFor(what = "ready", matcher = hasContentDescription("Listened"))
        return used.toSet()
    }

    private fun pickVoice(voice: Voice) {
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("opt-voice").performClick()
        compose.onNodeWithTag("voice-${voice.name}").performClick()
    }

    @Test
    fun importUsesThePhonesVoiceByDefault() {
        Options().save(ctx)
        assertEquals(setOf("android"), importAndCollectEngines())
    }

    @Test
    fun voiceDropDownDefaultsToThePhonesVoiceAndListsAllThree() {
        Options().save(ctx)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("opt-voice").assertTextContains(Voice.PHONE.label)
        compose.onNodeWithTag("opt-voice").performClick()
        for (v in Voice.values()) compose.onNodeWithTag("voice-${v.name}").assertExists()
    }

    @Test
    fun pickingKokoroMakesAnImportUseTheKokoroSynthesizer() = pickingAVoiceMakesAnImportUseIt(Voice.KOKORO)

    @Test
    fun pickingPiperMakesAnImportUseThePiperSynthesizer() = pickingAVoiceMakesAnImportUseIt(Voice.PIPER)

    private fun pickingAVoiceMakesAnImportUseIt(voice: Voice) {
        Options().save(ctx)
        Engines.prepareNeural = { _, _, progress -> progress(1f) }
        pickVoice(voice)
        waitFor(what = "voice ready", matcher = hasText("Voice ready"))
        assertEquals(voice, Options.load(ctx).voice)
        scenario?.close()
        assertEquals(setOf(voice.name), importAndCollectEngines())
    }

    @Test
    fun aFailedVoiceDownloadFallsBackToThePreviousVoiceAndSaysWhy() {
        Options().save(ctx)
        Engines.prepareNeural = { _, _, _ -> throw java.io.IOException("no network") }
        pickVoice(Voice.PIPER)
        waitFor(what = "failure", matcher = hasText("Voice download failed: no network"))
        compose.onNodeWithTag("opt-voice").assertTextContains(Voice.PHONE.label)
        assertEquals(Voice.PHONE, Options.load(ctx).voice)
    }

    @Test
    fun theOldNaturalVoiceSwitchMeansKokoro() {
        ctx.getSharedPreferences("settings", 0).edit().clear().putBoolean("natural", true).commit()
        assertEquals(Voice.KOKORO, Options.load(ctx).voice)
    }

    private fun getInstrumentationPrefsReset() = Options().save(ctx)

    private fun sentence(i: Int) = compose.onNodeWithTag("sentence-$i")

    @Smoke @Test
    fun importedPdfBecomesAnAudiobookWithTranscript() {
        openImportedBook()
        // OCR is real, so allow it a little slack: each fixture sentence's first words must be there.
        for ((i, s) in Fixtures.sentences.withIndex()) {
            waitFor(what = "sentence $i", matcher = hasText(s.split(" ").take(4).joinToString(" "), substring = true, ignoreCase = true))
        }
        sentence(0).assertIsSelected()
    }

    @Smoke @Test
    fun playingMovesTheHighlightAlongTheTranscript() {
        openSeededBook()
        compose.onNodeWithTag("play").performClick()
        waitFor(timeout = 30_000, what = "sentence 1 highlighted", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-1")
            and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag("play").performClick()
    }

    @Smoke @Test
    fun tappingTheSpeakingParagraphPausesAndResumesAndAnotherParagraphSeeks() {
        openSeededBook()
        compose.onNodeWithTag("play").performClick()
        compose.onNodeWithTag("play").assertTextEquals("Pause")
        sentence(0).assertIsSelected()

        sentence(0).performClick()
        compose.onNodeWithTag("play").assertTextEquals("Play")
        sentence(0).assertIsSelected()
        sentence(0).performClick()
        compose.onNodeWithTag("play").assertTextEquals("Pause")
        // Resuming carries on from where it stopped: still in this paragraph, then on to the next.
        sentence(0).assertIsSelected()
        waitFor(timeout = 30_000, what = "sentence 1 highlighted after resuming", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "sentence-1")
            and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        sentence(2).performClick()
        sentence(2).assertIsSelected()
    }

    @Smoke @Test
    fun theFirstTapOnAnotherSentenceAfterOpeningTheBookStartsSpeaking() {
        openSeededBook()
        compose.onNodeWithTag("play").assertTextEquals("Play")
        sentence(2).performClick()
        sentence(2).assertIsSelected()
        compose.onNodeWithTag("play").assertTextEquals("Pause")
        compose.onNodeWithTag("play").performClick()
    }

    @Smoke @Test
    fun tappingASentenceSeeksToIt() {
        openSeededBook()
        sentence(2).performClick()
        sentence(2).assertIsSelected()
    }

    @Smoke @Test
    fun highlightedParagraphsShowInTheNotesPanelWithTheirTextAndNote() {
        openSeededBook()
        val color = SemanticsMatcher.expectValue(HighlightKey, "YELLOW")
        sentence(1).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        sentence(2).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        sentence(2).performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performTextInput("check this claim")
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performImeAction()
        compose.onNodeWithTag("notes").performClick()
        waitFor(what = "rows", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "panel-note-1"))
        // Row 0 is the highlight alone, row 1 the highlight with its note under it; both quote the paragraph in its pen colour.
        compose.onNodeWithTag("panel-highlight-0", useUnmergedTree = true).assert(color)
        compose.onNodeWithTag("panel-highlight-1", useUnmergedTree = true).assert(color)
        compose.onNodeWithTag("panel-note-text-0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("panel-note-text-1", useUnmergedTree = true).assertTextEquals("check this claim")

        // Swiping a highlight with no note removes the highlight and takes the row away.
        compose.onNodeWithTag("panel-note-0").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("panel-highlight-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("panel-highlight-0", useUnmergedTree = true).assert(color)
        compose.onNodeWithTag("panel-note-text-0", useUnmergedTree = true).assertTextEquals("check this claim")
    }

    @Smoke @Test
    fun theNotesPanelHasNoAddButton() {
        openSeededBook()
        compose.onNodeWithTag("notes").performClick()
        compose.onNodeWithTag("notes-panel").assertIsDisplayed()
        compose.onNodeWithTag("panel-add-note").assertDoesNotExist()
    }

    @Smoke @Test
    fun swipingRightOnAnOpenNoteFieldCancelsItAndSavesNothing() {
        openSeededBook()
        val id = Library.books.value.single().id
        val input = { compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true) }
        // On the field, then on the paragraph's text above it; a swipe left keeps the field and what was typed.
        for (swipeOn in listOf({ input() }, { sentence(2) })) {
            sentence(2).performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
            input().performTextInput("half written")
            input().performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.25f) }
            compose.waitForIdle()
            input().assertIsDisplayed()
            input().assertTextEquals("half written")
            swipeOn().performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("paragraph-note-input", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            assertTrue("a cancelled note is not saved", ParagraphNotes.load(ctx, id).isEmpty())
            compose.onAllNodesWithTag("paragraph-note-2", useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Smoke @Test
    fun swipingRightOnTheNotesPanelNoteFieldCancelsItInsteadOfClosingThePanel() {
        openSeededBook()
        val id = Library.books.value.single().id
        sentence(2).performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performTextInput("kept")
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performImeAction()
        compose.onNodeWithTag("notes").performClick()
        waitFor(what = "row", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "panel-note-0"))
        compose.onNodeWithTag("panel-note-0").performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.9f) }
        compose.onNodeWithTag("panel-note-input", useUnmergedTree = true).performTextInput(" and more")
        compose.onNodeWithTag("panel-note-input", useUnmergedTree = true).performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("panel-note-input", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("notes-panel").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals("a cancelled edit is not saved", "kept", ParagraphNotes.load(ctx, id).single().text)
    }

    @Smoke @Test
    fun swipingTheOpenNotesPanelRightClosesIt() {
        openSeededBook()
        compose.onNodeWithTag("notes").performClick()
        compose.onNodeWithTag("notes-panel").assertIsDisplayed()
        compose.onNodeWithTag("notes-panel").performTouchInput { swipe(start = androidx.compose.ui.geometry.Offset(width * 0.1f, 20f), end = androidx.compose.ui.geometry.Offset(width * 0.9f, 20f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("notes").assertTextEquals("Notes")
    }

    /**
     * Chapter titles are headings in the transcript. Chapter One is a heading the text has (its sentence is the heading, drawn large);
     * the second chapter's title comes only from the pre-pass, so it gets a row of its own above the paragraph it opens.
     */
    @Smoke @Test
    fun chaptersShowAsHeadingsAboveTheirFirstParagraph() {
        val id = Fixtures.seedSpokenBook(ctx)
        val saved = Library.segments(ctx, id)
        Library.saveSegments(ctx, id, saved.mapIndexed { i, s -> if (i == 0) s.copy(text = "Chapter One") else s })
        Library.update(ctx, id) { it.copy(chapters = null, estimates = listOf(ChapterEstimate("The Zebra Years", null, null, listOf(saved[2].text)))) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(what = "shelf", matcher = hasContentDescription("Listened"))
        openShelfBook()
        waitFor(what = "second heading", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "chapter-2"))

        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(listOf("Chapter One", "The Zebra Years"), headings.map { it.config[SemanticsProperties.Text].joinToString("") })
        assertEquals(listOf(0, 2), headings.map { it.config[SemanticsProperties.TestTag].removePrefix("chapter-").toInt() })
        val rowTop = compose.onNodeWithTag("chapter-2", useUnmergedTree = true).getBoundsInRoot().top
        assertTrue("heading sits above its paragraph", rowTop < compose.onNodeWithTag("sentence-2").getBoundsInRoot().top)

        // The heading row is not a sentence: the paragraph under it still speaks when tapped.
        sentence(2).performClick()
        sentence(2).assertIsSelected()
        compose.onNodeWithTag("play").assertTextEquals("Pause")
    }

    @Smoke @Test
    fun notesPanelListsEveryNoteWithItsPlaceAndPlaysEditsAndDeletesThem() {
        // The second paragraph becomes a chapter heading, so a row can name its chapter. One note of each kind is saved before the book opens.
        val id = Fixtures.seedSpokenBook(ctx)
        val segments = Library.segments(ctx, id).mapIndexed { i, s -> if (i == 1) s.copy(text = "Chapter Two") else s }
        Library.saveSegments(ctx, id, segments)
        Library.update(ctx, id) { it.copy(chapters = null) } // The test edited the text after generation stored its chapters: have them detected again.
        Library.saveNotes(ctx, id, listOf(Note("early", segments[0].start + 100, "early remark")))
        ParagraphNotes.save(ctx, id, listOf(ParagraphNote(2, ParagraphNotes.hash(segments[2].text), "zebra claim")))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(what = "shelf", matcher = hasContentDescription("Listened"))
        openShelfBook()
        compose.onNodeWithTag("notes-panel").assertDoesNotExist()

        // The button says "Notes" while the panel is shut and is a close "x" once it is open.
        compose.onNodeWithTag("notes").assertTextEquals("Notes")
        compose.onNodeWithTag("notes").performClick()
        compose.onNodeWithTag("notes").assertContentDescriptionEquals("Close notes")
        compose.onNodeWithTag("notes").performClick()
        waitFor(what = "panel closed", matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "notes"))
        compose.onNodeWithTag("notes-panel").assertDoesNotExist()
        compose.onNodeWithTag("notes").assertTextEquals("Notes")
        compose.onNodeWithTag("notes").performClick()

        // The panel slides in over 80% of the text area and lists both kinds in reading order, each with its % through the book and its chapter.
        waitFor(what = "panel row", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "panel-note-1"))
        compose.waitForIdle()
        val share = compose.onNodeWithTag("notes-panel").getBoundsInRoot().width.value / compose.onRoot().getBoundsInRoot().width.value
        assertTrue("panel should cover 80% of the width, covers $share", share in 0.78f..0.82f)
        val total = segments.last().end
        val early = (100 * 100 / total).toInt()
        val zebra = (segments[2].start * 100 / total).toInt()
        compose.onNodeWithTag("panel-note-meta-0", useUnmergedTree = true).assertTextEquals("$early% · Start")
        compose.onNodeWithTag("panel-note-text-0", useUnmergedTree = true).assertTextEquals("early remark")
        compose.onNodeWithTag("panel-note-meta-1", useUnmergedTree = true).assertTextEquals("$zebra% · Chapter Two")
        compose.onNodeWithTag("panel-note-text-1", useUnmergedTree = true).assertTextEquals("zebra claim")

        // One tap on a row seeks to the note and starts the audio.
        compose.onNodeWithTag("play").assertTextEquals("Play")
        compose.onNodeWithTag("panel-note-1").performClick()
        sentence(2).assertIsSelected()
        compose.onNodeWithTag("play").assertTextEquals("Pause")

        // A swipe right edits the note in place.
        compose.onNodeWithTag("notes").performClick()
        waitFor(what = "panel row", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "panel-note-0"))
        compose.onNodeWithTag("panel-note-0").performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.9f) }
        compose.onNodeWithTag("panel-note-input", useUnmergedTree = true).performTextClearance()
        compose.onNodeWithTag("panel-note-input", useUnmergedTree = true).performTextInput("later remark")
        compose.onNodeWithTag("panel-note-input", useUnmergedTree = true).performImeAction()
        compose.onNodeWithTag("panel-note-text-0", useUnmergedTree = true).assertTextEquals("later remark")
        assertEquals(listOf("later remark"), Library.notes(ctx, id).map { it.text })

        // A swipe left deletes it.
        compose.onNodeWithTag("panel-note-1").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        waitFor(what = "row gone", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "panel-note-text-0"))
        compose.onNodeWithTag("panel-note-1", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(ParagraphNotes.load(ctx, id).isEmpty())
        assertEquals(listOf("later remark"), Library.notes(ctx, id).map { it.text })
    }

    @Smoke @Test
    fun swipingAParagraphRightOpensAnInlineNoteThatSurvivesRestart() {
        openSeededBook()
        // Holding a paragraph is not the gesture: it opens nothing.
        sentence(1).performTouchInput { longClick(durationMillis = 1100) }
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).assertDoesNotExist()

        sentence(2).performTouchInput { swipeRight(startX = width * 0.25f, endX = width * 0.9f) }
        val input = compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true)
        input.assertIsDisplayed()
        // The field sits inside paragraph 2's own row, under its text.
        val row = sentence(2).getBoundsInRoot()
        val field = input.getBoundsInRoot()
        assertTrue("field $field should be inside row $row", field.top >= row.top && field.bottom <= row.bottom)
        input.performTextInput("verify the zebra count")
        input.performImeAction()
        waitFor(what = "marker", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "paragraph-note-marker-2"))
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).assertDoesNotExist()

        val id = Library.books.value.single().id
        scenario!!.close()
        assertEquals(listOf("verify the zebra count"), ParagraphNotes.load(ctx, id).map { it.text })

        openBookAgain()
        waitFor(what = "marker after restart", unmerged = true, matcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "paragraph-note-marker-2"))
        compose.onNodeWithTag("paragraph-note-text-2", useUnmergedTree = true).assertTextEquals("verify the zebra count")

        // Tapping the note edits it in place; clearing it removes it.
        compose.onNodeWithTag("paragraph-note-marker-2", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performTextClearance()
        compose.onNodeWithTag("paragraph-note-input", useUnmergedTree = true).performImeAction()
        compose.onNodeWithTag("paragraph-note-marker-2", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(ParagraphNotes.load(ctx, id).isEmpty())
    }

    @Test
    fun playbackSpeedCyclesAndIsRemembered() {
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        openSeededBook()
        compose.onNodeWithTag("speed").assertTextEquals("1.0×")
        compose.onNodeWithTag("speed").performClick()
        compose.onNodeWithTag("speed").assertTextEquals("1.25×")

        scenario!!.close()
        openBookAgain()
        compose.onNodeWithTag("speed").assertTextEquals("1.25×")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    private fun openBookAgain() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(what = "ready", matcher = hasContentDescription("Listened"))
        compose.onNodeWithTag("book-$title").performClick()
    }

    @Smoke @Test
    fun theShareMenuOffersEveryFormatAndTheZipBackupRestoresAsABook() {
        openSeededBook()
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithTag("backup").assertDoesNotExist()
            compose.onNodeWithContentDescription("Share").performClick()
            for (label in listOf("Audiobook (one M4B)", "EPUB", "Zip backup", "One M4A per chapter")) compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("Zip backup").performClick()
            compose.waitUntil(30_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
            val chooser = Intents.getIntents().first { it.action == Intent.ACTION_CHOOSER }
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/zip", send.type)
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            val names = ArrayList<String>()
            ZipInputStream(ctx.contentResolver.openInputStream(uri)!!).use { z ->
                while (true) { val e = z.nextEntry ?: break; names += e.name; assertTrue("${e.name} is empty", z.readBytes().isNotEmpty()) }
            }
            assertEquals(listOf(Files.PDF, Files.AUDIO, Files.TRANSCRIPT, Files.NOTES, Files.META).sorted(), names.sorted())
            // The zip the share sheet receives is what Import restores.
            assertEquals(listOf(title), Backup.restore(ctx, uri))
        } finally {
            Intents.release()
        }
    }

    @Smoke @Test
    fun aPdfWithNoTextFailsWithAReason() {
        val blank = File(ctx.cacheDir, "blank.pdf")
        val doc = android.graphics.pdf.PdfDocument()
        doc.finishPage(doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()))
        blank.outputStream().use { doc.writeTo(it) }
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", blank), "application/pdf")
        )
        waitFor(what = "failure", matcher = hasText("No text found", substring = true))
    }

    /** Runs [body] with every PDF page read as one fixed sentence, so an import ends in a moment instead of waiting for OCR. [body] must wait for the import to finish. */
    private fun withQuickOcr(body: () -> Unit) {
        Engines.recognisePage = { listOf("A short sentence that the import tests read in a moment.") }
        Engines.surveyPages = { emptyList() }
        try { body() } finally { Engines.recognisePage = null; Engines.surveyPages = null }
    }

    private fun launchViewing(file: File, type: String = "application/pdf") {
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file), type)
        )
    }

    @Test
    fun aPdfSharedFromAnotherAppJoinsTheShelfAndIsMade() = withQuickOcr {
        scenario = ActivityScenario.launch(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, Fixtures.pdf(ctx)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        waitFor(what = "ready", matcher = hasContentDescription("Listened"))
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun aShareWithNoFileAddsNothingAndTheShelfStaysEmpty() {
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/pdf"))
        waitFor(what = "empty shelf", matcher = hasText("No audiobooks yet. Import a PDF or EPUB."))
        Thread.sleep(1_000) // An import runs in the background; give a wrongly started one time to show.
        assertTrue(Library.books.value.isEmpty())
    }

    @Test
    fun aFileThatCannotBeOpenedFailsTheBookWithAReasonInsteadOfCrashing() {
        launchViewing(File(ctx.cacheDir, "missing.pdf").also { it.delete() })
        waitFor(what = "failure", matcher = hasText("Could not read the PDF", substring = true))
        assertEquals(Status.FAILED, Library.books.value.single().status)
    }

    @Test
    fun aFileThatIsNotAPdfFailsWithAReason() {
        launchViewing(File(ctx.cacheDir, "notes.pdf").also { it.writeText("this is plainly not a pdf") })
        compose.waitUntil(60_000) { Library.books.value.singleOrNull()?.status == Status.FAILED }
        val reason = Library.books.value.single().error
        assertTrue("a reason is shown, not a blank: $reason", !reason.isNullOrBlank())
        waitFor(what = "the reason on the shelf", matcher = hasText(reason!!, substring = true))
    }

    @Test
    fun importingTheSamePdfTwiceMakesTwoBooksWithTheSameTitle() = withQuickOcr {
        launchWithPdf()
        waitFor(what = "first ready", matcher = hasContentDescription("Listened"))
        val second = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Fixtures.pdf(ctx), "application/pdf")
        )
        try {
            compose.waitUntil(60_000) { Library.books.value.size == 2 && Library.books.value.all { it.status == Status.READY } }
            assertEquals(listOf(title, title), Library.books.value.map { it.title })
            assertEquals(2, Library.books.value.map { it.id }.toSet().size)
        } finally {
            second.close()
        }
    }
}
