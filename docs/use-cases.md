# Use cases

This document lists what Screenplay lets a listener do. Each entry is one use case: an id, a goal in the listener's words, the behaviour in a few lines, and the tests that prove it.

A test is named `ClassName.methodName`. Unit tests live in `app/src/test` and device tests in `app/src/androidTest`. Device tests marked `@Smoke` are the always-run set.

`scripts/check-use-cases.sh` reads this file. It fails when a use case id appears twice, when a use case has no `Tests:` line, or when a named test no longer exists. `./gradlew testDebugUnitTest` runs it. When you rename or delete a test, update the entry in the same change.

`Tests: none yet` means no test proves the use case. These entries are collected under [Gaps](#gaps). A line starting with `Unchecked:` names behaviour that no test verifies on a real device.

## Contents

- [Importing](#importing): UC-01 to UC-13
- [Listening](#listening): UC-14 to UC-20
- [Reading along](#reading-along): UC-21 to UC-26
- [Notes and highlights](#notes-and-highlights): UC-27 to UC-33
- [Library](#library): UC-34 to UC-40
- [Settings and looks](#settings-and-looks): UC-41 to UC-46
- [Gaps](#gaps)

## Importing

### UC-01 Make an audiobook from a PDF on the shelf

Goal: turn a PDF on my phone into an audiobook with a transcript.

- Tap "Import PDF or EPUB" on the shelf and pick a file.
- The app reads each page with on-device OCR, speaks the text, and shows the book as ready.
- The transcript shows the sentences of the book, and the first one is selected.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanMoveUp`

Unchecked: OCR and speech on the phone itself, with a real scan.

### UC-02 Open a PDF or EPUB from another app

Goal: start a book from a file manager or a download without opening Screenplay first.

- Open a PDF or an EPUB with Screenplay ("Open with").
- The book joins the import queue and is made like any other.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`
- `EpubUiTest.importsAnEpubWithItsTitleAuthorChaptersAndText`

### UC-03 Share a PDF or EPUB to Screenplay

Goal: send a book to the app from the share sheet of another app.

- Share a PDF or an EPUB to Screenplay with `ACTION_SEND`.
- The book joins the import queue.

Tests: none yet

### UC-04 See why a PDF cannot be made

Goal: learn why a book failed, instead of seeing a spinner forever.

- Import a PDF that has no readable text.
- The book shows as failed with a reason.

Tests:
- `BookUiTest.aPdfWithNoTextFailsWithAReason`

### UC-05 Have scanned pages read as clean prose

Goal: hear the book, not the page furniture.

- Running heads, page numbers, footnotes, contents lists, index pages and tables are left out of the speech.
- Words split across lines or pages are joined, and OCR slips such as `I` read as `T` are repaired.
- Sentences are cut at sensible places.

Tests:
- `OcrFixtureTest.originOfSpecies`
- `OcrFixtureTest.declineAndFall`
- `OcrFixtureTest.varietiesOfReligiousExperience`
- `OcrFixtureTest.varietiesPrefaceThenContents`
- `LayoutTest.dropsRunningHeadsBelowTheEdgeBand`
- `LayoutTest.dropsFootnotesInSmallerTypeBelowTheBody`
- `LayoutTest.dropsIndexPages`
- `LayoutTest.joinsLinesAndHyphenatedWords`
- `TextTest.dropsPageNumbersAndStrayBlocks`
- `TextTest.mergesParagraphCutByPageBreak`
- `TextTest.fixesThePronounIOcrReadsAsT`
- `CleanupTest.stripsFootnoteNumbersGluedToPunctuation`
- `CleanupTest.dropsContentsAndAbbreviationsWithPageNumbersLongEntriesAndQuestions`

Unchecked: OCR quality on the phone's own scans, and speed against accuracy on the phone.

### UC-06 Get the title and author from the ISBN

Goal: see a real title and author on the shelf, not a file name.

- The app looks for an ISBN on the first pages and asks the Open Library catalogue.
- The shelf shows the title and author found. A failed lookup keeps the file title, and the book still finishes.

Tests:
- `IsbnImportTest.theShelfShowsTheTitleAndAuthorFoundFromTheIsbn`
- `IsbnImportTest.aFailedLookupKeepsTheFileTitleAndTheBookStillFinishes`
- `IsbnTest.findsAHyphenatedIsbn13AfterItsLabel`
- `IsbnTest.rejectsABadCheckDigit`
- `IsbnTest.readsTitleAndAuthorsFromAnOpenLibraryReply`

Unchecked: the real Open Library service.

### UC-07 Start listening before the book is finished

Goal: not wait for a long book to be made.

- While a book is imported, the reader shows reading and speaking progress, and Play stays grey until speech exists.
- Audio of the first pages plays before the last page is read. Text that is read but not yet spoken shows in the reader.
- A finished book has the same transcript and audio as one read whole.

Tests:
- `ImportProgressTest.readerGreysOutPlayUntilSpeechExistsAndShowsPageAndSpeechProgressWhileImporting`
- `StreamingImportTest.audioOfTheFirstPagesIsPlayableBeforeTheLastPageHasBeenRead`
- `StreamingImportTest.textThatIsReadButNotYetSpokenShowsInTheReader`
- `StreamingImportTest.aFinishedBookHasTheSameTranscriptAndAudioAsReadingTheWholeBookFirst`
- `TextStreamTest.streamingPagesGivesTheSentencesOfTheWholeBook`
- `BookProgressTest.speechProgressIsTheSpokenShareOfTheWordsReadScaledByThePagesRead`
- `BookLabelTest.readingShowsItsPercentageAndTheWordsSpokenSoFar`

### UC-08 Make an audiobook from an EPUB

Goal: listen to a book I already own as an EPUB.

- Import an EPUB from the shelf.
- The title, author, chapters and text come from the file. A contents list the EPUB prints itself does not make chapters twice.
- Both EPUB 3 and EPUB 2 files are read, and the spine decides the order.

Tests:
- `EpubUiTest.importsAnEpubWithItsTitleAuthorChaptersAndText`
- `EpubUiTest.anEpubThatPrintsItsOwnContentsListHasEachChapterOnce`
- `EpubTest.readsTheTitleAuthorParagraphsAndNestedChapters`
- `EpubTest.anEpub2UsesItsNcxAndAnAnchorInsideAFile`
- `EpubTest.anEpubWithNoNavGetsItsChaptersFromTheHeadings`
- `BackupExtractTest.anEpubBookNeedsNoPdf`

### UC-09 Be told why an EPUB cannot be imported

Goal: understand a refusal.

- An EPUB with DRM is refused with a clear message. A damaged file is refused, not crashed on.
- An EPUB whose fonts are only obfuscated is not taken for DRM.

Tests:
- `EpubUiTest.saysWhyAnEpubWithDrmCannotBeImported`
- `EpubTest.aDrmEpubIsRefusedWithAClearMessage`
- `EpubTest.anObfuscatedFontIsNotDrm`
- `EpubTest.malformedFilesAreRefusedNotCrashedOn`

### UC-10 Queue several books and have them made one at a time

Goal: pick many books and leave the phone to work.

- Books are made in queue order, one at a time. The book being made shows under "Importing" and the others wait as "Queued".
- A failed book leaves the queue and the next is made.
- When all are made, the section goes and the books sit under "Imported".

Tests:
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanMoveUp`
- `ImportQueueTest.booksAreMadeOneAtATimeInQueueOrder`
- `ImportQueueTest.aFailedBookLeavesTheQueueAndTheNextIsMade`
- `ImportQueueTest.aWaitingBookIsNotMadeWhileAnotherIsHeld`
- `ImportQueueTest.anEmptyQueueHasNothingImportingSoTheShelfShowsNoImportingSection`
- `ImportQueueTest.labelsShowPositionWithTheBookBeingMadeFirst`

### UC-11 Reorder the import queue

Goal: have the book I want next made next.

- Every book in the queue has a drag handle, the book being made too. Holding the handle starts the drag at once; a long press on the row works as well.
- A waiting book also has a "move up" button. It never takes a waiting book above the book being made.
- Whatever is dropped first is made first. A book being made that is passed, or dragged down, is stopped and waits in its new place.
- A stopped book keeps the speech it made: when it is made again it carries on after it.

Tests:
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanMoveUp`
- `ImportQueueTest.movingUpSwapsWithTheWaitingBookAheadAndTheButtonNeverPassesTheBookBeingMade`
- `ImportQueueTest.aWaitingBookDroppedBetweenWaitingBooksTakesThatPlaceAndTheRunningBookKeepsRunning`
- `ImportQueueTest.aBookDroppedFirstIsMadeFirstAndTheRunningBookItPassedIsStoppedAndWaitsNextInLine`
- `ImportQueueTest.theRunningBookDraggedDownIsStoppedAndTheBookItPassedIsMadeInsteadOfIt`
- `ImportQueueTest.aBookStoppedToLetAnotherGoFirstIsMadeAgainInTheSameRun`
- `SpeechTest.aStoppedBookCarriesOnFromItsKeptSpeechWithoutSpeakingItAgain`
- `SpeechTest.keptSpeechIsOnlyWhatIsWholeInTheAudioOnDisk`

### UC-12 Keep making books after the app restarts

Goal: not lose the queue when the app or phone stops.

- After a restart, the interrupted book is made again before the waiting ones.

Tests:
- `ImportQueueTest.aRestartMakesTheInterruptedBookAgainBeforeTheWaitingOnes`

Unchecked: a real process kill, and the foreground job that keeps a long book going when the app is closed.

### UC-13 Find a public-domain book and add it to the queue

Goal: get a book without hunting for a file.

- "Find books" on the shelf searches Project Gutenberg through Gutendex, by genre and popularity.
- Tapping a book adds it to the import queue as an EPUB. A book with no EPUB says so and sends no request.
- When the catalogue cannot be reached, the screen shows a friendly message.
- The app waits after a failure, honours the server's retry delay, and names itself in its user agent.

Tests:
- `FindBooksUiTest.searchesByGenreAndPopularityThenAddsTheTappedBookToTheImportQueue`
- `FindBooksUiTest.saysWhenABookHasNoEpubAndSendsNoRequestForIt`
- `FindBooksUiTest.showsAFriendlyMessageWhenTheCatalogueCannotBeReached`
- `GutendexTest.turnsFiltersIntoTheCatalogueRequest`
- `GutendexTest.leavesOutBooksInCopyrightAndAnythingNotText`
- `GutendexTest.waitsAfterAFailureAndDoublesTheWaitUpToALimit`
- `GutendexTest.followsTheServersRetryAfterButNeverWaitsMoreThanTheLimit`
- `GutendexTest.theProjectGutenbergLicenceSurvivesReadingAndWritingTheBook`

Unchecked: the real Gutendex service. The tests use a fake network.

## Listening

### UC-14 Play and pause

Goal: start and stop the book.

- Play starts the speech from the current paragraph, and the same button pauses it.
- Pausing and playing again carries on from where it stopped.
- Stopping when playback has caught up with the audio of a book still being made does not crash.

Tests:
- `BookUiTest.playingMovesTheHighlightAlongTheTranscript`
- `LivePlayerTest.resumingAfterAPauseKeepsAdvancingFromWhereItStopped`
- `LivePlayerTest.stoppingPlaybackThatHasCaughtUpWithTheAudioDoesNotCrash`

### UC-15 Change the playback speed

Goal: listen faster or slower.

- The speed button cycles through the speeds.
- The app remembers the speed.

Tests:
- `BookUiTest.playbackSpeedCyclesAndIsRemembered`

### UC-16 Skip back and forward 10 seconds

Goal: replay a missed line or skip ahead.

- The notification and the media session offer back and forward buttons that move the position by 10 seconds.
- The position stops at the start and at the end of the audio.

Tests:
- `SkipTest.theStepIsTenSeconds`
- `SkipTest.skipsByTheStepInEitherDirection`
- `SkipTest.stopsAtTheStartAndAtTheEndOfTheAudio`
- `MediaNotificationTest.theNotificationShowsTheBookAndItsButtonsAndSeekAndHeadsetKeysDriveThePlayer`

### UC-17 Fall asleep to the book

Goal: have playback stop by itself.

- The sleep menu offers Off, a number of minutes, or the end of the chapter.
- The timer counts down only while playing, fires once, and pauses the speech.
- The mini player shows the time left.

Tests:
- `SleepTimerTest.countsDownWhilePlayingAndFiresOnceAtZero`
- `SleepTimerTest.pausedTimeDoesNotCount`
- `SleepTimerTest.offCancelsAndANewTimerReplacesTheOld`
- `SleepTimerTest.chapterEndFiresWhenPlaybackReachesIt`
- `SleepTimerUiTest.theTimerCountsInTheServiceAndPausesTheSpeechWhenItRunsOut`

### UC-18 Carry on where I stopped

Goal: reopen a book at the place I reached, and see how far I am.

- The app saves the position once per paragraph while playing, and when playback ends.
- Reopening the book shows the paragraph reached. The shelf and the reader show the percentage played.
- A book that played to the end is marked finished, and playing it again clears that.

Tests:
- `PlaceRestoredTest.theBookReopensOnTheParagraphReachedAndTheLibraryShowsThePercentPlayed`
- `PositionTest.resumeIsTheStartOfTheParagraphHoldingThePosition`
- `PositionTest.savesOncePerParagraphWhilePlayingOnly`
- `PositionTest.savesFinishedWhenPlaybackStopsAtTheEndAndPlayingAgainClearsIt`
- `PositionTest.percentIsPositionOverDurationWithTheEndsIncluded`
- `PositionTest.aBookNeverPlayedHasNoPercentage`

Unchecked: a real force-quit of the app while playing.

### UC-19 Control playback outside the app

Goal: pause, skip and seek from the notification, the lock screen or a headset.

- A media notification shows the book and its buttons. Seek and headset keys drive the player.
- A mini player on the shelf shows the playing book, a play and pause button, and a close button.

Tests:
- `MediaNotificationTest.theNotificationShowsTheBookAndItsButtonsAndSeekAndHeadsetKeysDriveThePlayer`

Unchecked: the look of the notification on the lock screen, and a real Bluetooth headset.

### UC-20 Hear years and numbers read naturally

Goal: not hear "nineteen oh five" as digits.

- Years, decades and ranges are spoken in words. The transcript keeps the digits.

Tests:
- `SpokenYearsTest.aYearReachesTheVoiceInWordsWhileTheTranscriptKeepsTheDigits`
- `SpokenTextTest.nineteenHundredsAreReadAsTwoPairs`
- `SpokenTextTest.decadesAreReadInThePlural`
- `SpokenTextTest.rangesAreReadWithTo`
- `SpokenTextTest.amountsAndOtherNumbersAreLeftAlone`

## Reading along

### UC-21 See the sentence being spoken

Goal: follow the text while I listen.

- The transcript highlights the speaking sentence and scrolls it into view.

Tests:
- `BookUiTest.playingMovesTheHighlightAlongTheTranscript`

### UC-22 Tap the text to jump

Goal: move to a place by touching it.

- Tapping a sentence seeks to it and speaks from there.
- Tapping the speaking paragraph pauses it, and tapping again resumes it.
- The first tap after opening a book starts speaking.

Tests:
- `BookUiTest.tappingASentenceSeeksToIt`
- `BookUiTest.tappingTheSpeakingParagraphPausesAndResumesAndAnotherParagraphSeeks`
- `BookUiTest.theFirstTapOnAnotherSentenceAfterOpeningTheBookStartsSpeaking`

### UC-23 Drag the scroll bar through the book

Goal: move quickly through a long book.

- The scroll bars at the edges of the transcript jump through the whole book, including text that is read but not yet spoken.
- A label shows the chapter and the time while dragging.

Tests:
- `StreamingImportTest.theScrollBarReachesTextThatIsReadButNotYetSpoken`

Unchecked: the chapter label while dragging, and the feel of the drag.

### UC-24 See chapters and jump to one

Goal: find a chapter.

- Chapter headings show above their first paragraph.
- The chapter menu lists the chapters, and choosing one jumps there.
- A book that is read but not yet spoken lists its chapters too.
- A book stored without chapters gets them detected on first use and keeps them.

Tests:
- `BookUiTest.chaptersShowAsHeadingsAboveTheirFirstParagraph`
- `ChaptersWithoutAudioTest.aBookReadButNotSpokenListsItsChaptersAndJumpsToOne`
- `ChaptersWithoutAudioTest.aBookStoredWithoutChaptersGetsThemDetectedOnFirstUseAndKeepsThem`
- `ChapterDetectionTest.detectsHeadingsInTextThatHasNoAudio`

### UC-25 Find chapters from the contents page

Goal: get real chapter titles from a scanned book.

- The contents page gives the titles and the pages. The first chapter's title gives the offset between printed pages and PDF pages.
- A book with no contents page has no plan, and its chapters come from the headings in the text.

Tests:
- `ChapterEstimatorTest.contentsRowsWithLeadersGiveTitlesAndPredictedPages`
- `ChapterEstimatorTest.noContentsPageGivesNoPlan`
- `ChapterEstimatorTest.withoutRunningNumbersTheFirstChapterTitleGivesTheOffset`
- `ChapterFixtureTest.originContents`
- `ChapterFixtureTest.varietiesContents`
- `ChapterFixtureTest.gibbonAnalyticContents`
- `CleanupTest.findsChaptersFromContents`
- `CleanupTest.anEstimatedChapterWhoseAnchorIsNotInTheTextIsLeftOut`

### UC-26 Read a long title without losing the buttons

Goal: use the book screen's buttons whatever the title.

- A long title takes two lines and then an ellipsis, so the Back, Export and Back up buttons stay in view.
- The controls and the progress under them are centred.

Tests:
- `BookHeaderTest.longTitleKeepsButtonsInViewAndEndsInEllipsis`
- `ControlsCentredTest.theButtonsAndTheProgressUnderThemAreCentred`

## Notes and highlights

### UC-27 Add a note to a paragraph

Goal: write down a thought at the place that prompted it.

- Swipe a paragraph right to open a note field under it. Done saves the note.
- Swiping right on the open field cancels it and saves nothing.
- The note survives a restart, and a marker shows on its paragraph.
- Notes start with a capital letter.

Tests:
- `BookUiTest.swipingAParagraphRightOpensAnInlineNoteThatSurvivesRestart`
- `BookUiTest.swipingRightOnAnOpenNoteFieldCancelsItAndSavesNothing`
- `NoteKeyboardTest.notesStartSentencesWithACapital`
- `NoteKeyboardTest.doneStillSavesTheNote`

### UC-28 Review, play, edit and delete my notes

Goal: go through everything I wrote.

- The Notes button opens a panel that lists every note with its place, and every highlighted paragraph with its text.
- A note can be played from, edited or deleted. The panel has no add button.
- Swiping the panel right closes it, or first cancels an open note field.

Tests:
- `BookUiTest.notesPanelListsEveryNoteWithItsPlaceAndPlaysEditsAndDeletesThem`
- `BookUiTest.highlightedParagraphsShowInTheNotesPanelWithTheirTextAndNote`
- `BookUiTest.theNotesPanelHasNoAddButton`
- `BookUiTest.swipingRightOnTheNotesPanelNoteFieldCancelsItInsteadOfClosingThePanel`
- `BookUiTest.swipingTheOpenNotesPanelRightClosesIt`

### UC-29 Highlight a paragraph

Goal: mark a paragraph to find again.

- Swipe a paragraph left to highlight it in yellow. Swiping again removes the highlight.
- A highlight follows its paragraph when the transcript shifts.

Tests:
- `HighlightUiTest.swipingLeftHighlightsInYellowAndAgainRemovesIt`
- `HighlightsTest.togglingHighlightsInTheDefaultYellowThenRemovesIt_andAColourChangesAnExistingOne`
- `HighlightsTest.aHighlightFollowsItsParagraphWhenTheTranscriptShifts`

### UC-30 Choose a highlight colour

Goal: colour-code my highlights.

- Holding the left swipe opens a six-slice colour wheel. Releasing on a slice picks its colour, and releasing in the centre cancels.
- Yellow is the default.

Tests:
- `HighlightUiTest.holdingTheSwipeOpensTheSixSliceWheelAndReleasingOnASlicePicksItsColour`
- `HighlightUiTest.releasingTheWheelInItsCentreCancels`
- `HighlightsTest.theWheelHasSixEqualSlicesClockwiseFromTheTop`
- `HighlightsTest.yellowIsTheDefaultAndFirstInTheWheel_orangeStaysAChoice`

### UC-31 Read highlighted text in light and dark themes

Goal: keep highlighted text readable.

- Every highlight colour keeps the transcript text readable in both themes. A highlight is one translucent band per line, like a marker.

Tests:
- `HighlightsTest.everyHighlightColourKeepsTheTranscriptTextReadableInBothThemes`
- `HighlightsTest.theHighlightIsTranslucent`
- `HighlightsTest.oneBandPerLineEachWithItsOwnExtent_aShortLastLineGetsAShortBand`
- `HighlightUiTest.rendersTheMarkerLookToAnImage`

Unchecked: how the highlight looks to a person on a real screen.

### UC-32 Fix a paragraph the OCR got wrong

Goal: correct or split a paragraph.

- Long-press a paragraph and choose Edit. A new line in the text splits it into two paragraphs.
- The audio is not spoken again. The paragraph keeps its span of the audio, and a split divides the span by text length.
- The highlight stays on both parts, the note stays on the first, and later notes, highlights and chapters move down.

Tests:
- `ParagraphMenuUiTest.editingAParagraphIntoTwoKeepsTheHighlightOnBothAndTheNoteOnTheFirst`
- `ParagraphEditTest.splittingKeepsTheHighlightOnBothPartsAndTheNoteOnTheFirstOnly`
- `ParagraphEditTest.splittingDividesTheOriginalSpanByTextLengthAndLeavesNoGap`
- `ParagraphEditTest.splittingMovesLaterNotesHighlightsAndChaptersDownByOne`
- `ParagraphEditTest.editingWithoutASplitKeepsSpanNoteAndHighlightOnTheNewText`
- `ParagraphEditTest.blankLinesAndSpacesMakeNoEmptyParagraph`

Unchecked: the edited text is not heard. The audio still says the original words.

### UC-33 Delete a paragraph

Goal: remove a page number or junk that was read aloud.

- Long-press a paragraph and choose Delete. A paragraph with a highlight asks first, and Cancel keeps it.
- Its note and highlight go with it, and later notes move up.
- The audio still plays whole, because a neighbour takes the deleted paragraph's span.

Tests:
- `ParagraphMenuUiTest.deletingAParagraphRemovesItAndMovesLaterNotesUp`
- `ParagraphMenuUiTest.deletingAHighlightedParagraphAsksFirstAndCancelKeepsIt`
- `ParagraphEditTest.deletingRemovesTheParagraphItsNoteAndHighlightAndShiftsLaterOnesUp`
- `ParagraphEditTest.deletingKeepsTheAudioCoveredByGivingTheSpanToTheNeighbour`

## Library

### UC-34 Delete a book

Goal: free space, without deleting by accident.

- Delete on the shelf asks first. Cancel keeps the book, and confirming removes the book with its PDF, audio, transcript and notes.

Tests:
- `DeleteConfirmTest.deleteAsksFirstCancelKeepsAndConfirmDeletes`

### UC-35 Back up one book

Goal: keep a copy of a book and my notes outside the app.

- Back up on the book screen shares one zip through the share sheet.
- The zip holds the PDF or EPUB, the audio, the transcript, the notes, the highlights and the details.

Tests:
- `BookUiTest.backupSharesAZipOfPdfAudioTranscriptAndNotes`
- `BackupExtractTest.extractsTheKnownEntriesAndIgnoresOthers`
- `BackupExtractTest.extractsTheHighlights`

Unchecked: sending the zip to a real target app such as a cloud drive.

### UC-36 Restore a book from a backup

Goal: get a book back, with my notes.

- Import backup in the settings takes a book zip and adds it as a new book, with transcript, notes and details.
- A zip that is not a backup is refused with a message. A zip with path traversal writes nothing outside the book's folder.

Tests:
- `BackupImportTest.importsABackupAsANewBookWithTranscriptNotesAndDetails`
- `BackupImportTest.refusesAZipThatIsNotABackupAndSaysSo`
- `BackupExtractTest.rejectsAZipWithNeitherPdfNorEpub`
- `BackupExtractTest.rejectsAZipWithoutTheAudio`
- `BackupExtractTest.rejectsPathTraversalAndWritesNothingOutside`

### UC-37 Back up and restore the whole library

Goal: move all my books to a new phone.

- Export backup in the settings makes one zip of every book. Import backup restores every book in it.

Tests:
- `BackupImportTest.exportsTheWholeLibraryFromTheSettingsAndImportsItBack`

Unchecked: a library large enough to matter, in size and time.

### UC-38 Export a book as one audiobook file

Goal: listen in another audiobook player.

- Export, then "Audiobook (one M4B)", shares one M4B with the title, the author and the chapters.
- Export waits until the book is ready.

Tests:
- `ExportTest.exportsOneM4bWithChaptersTitleAndAuthor`
- `ExportTest.exportWaitsUntilTheBookIsReady`
- `ExportMetadataTest.metadataStatesTheFormatVersionAndBookDetails`

Unchecked: playing the file in a real third-party player.

### UC-39 Export one audio file per chapter

Goal: put chapters on a player that has no chapter support.

- Export, then "One M4A per chapter", shares one file per chapter. The durations match the chapters.

Tests:
- `ExportTest.exportsOneM4aPerChapterWithMatchingDurations`

### UC-40 Export a book as an EPUB

Goal: read the transcript, with its chapters and edits, in an e-reader.

- Export, then "Export as EPUB", shares an EPUB 3 file with the title, the author, the chapters and the paragraphs.
- Importing that file gives the same book back.

Tests:
- `EpubUiTest.exportsAnEpubThatImportsAgainAsTheSameBook`
- `EpubTest.aRoundTripKeepsTitleAuthorChaptersAndParagraphs`
- `EpubTest.aRoundTripKeepsTextBeforeTheFirstChapterOutOfTheContents`
- `EpubTest.theWrittenFileIsAnEpub3Container`

Unchecked: opening the file in a real e-reader.

## Settings and looks

### UC-41 Choose what happens to the text of new books

Goal: set the import options once.

- The cog on the shelf opens the import options: spell check, skip front matter, merge split sentences, and skip tables, lists and notes.
- Each switch shows its state, and the choice is kept across launches. It applies to books started after the change.

Tests:
- `BookUiTest.cogOpensSettingsAndSwitchShowsItsState`

### UC-42 Skip the front matter

Goal: start the speech at the first chapter, not at the copyright page.

- With "Skip front matter" on, speech starts at the first heading that is followed by prose. A book with no front matter is left alone.

Tests:
- `CleanupTest.trimsToFirstHeadingFollowedByProse`
- `CleanupTest.skipsAContentsEntryWithNoProseAfterIt`
- `CleanupTest.fallsBackToTheImprintBlock`
- `CleanupTest.leavesTextWithNoFrontMatterAlone`

### UC-43 Skip tables, lists and notes

Goal: not hear tables and lists read aloud.

- With "Skip tables, lists & notes" on, table cells, numeric lines and runs of short unpunctuated lines are dropped, and back matter is cut.

Tests:
- `CleanupTest.dropsTableCells`
- `CleanupTest.dropsNumericLinesButKeepsChapterHeadings`
- `CleanupTest.dropsRunsOfShortUnpunctuatedLines`
- `CleanupTest.cutsAtBackMatterInTheSecondHalfOnly`
- `TextStreamTest.backMatterIsNeverSpokenButTheTextBeforeItIsReleasedEarly`

### UC-44 Fix OCR typos with the spell checker

Goal: have small OCR slips repaired before they are spoken.

- With "Spell check" on, the app applies the checker's first suggestion for lowercase words that are close to the original.

Tests:
- `CleanupTest.appliesOnlyCloseSuggestionsToLowercaseWords`

Unchecked: the Android spell checker running on a real book.

### UC-45 Choose the voice

Goal: pick between the phone's voice and a more natural one.

- The voice menu offers the phone's own voice, Kokoro and Piper. The phone's voice is the default.
- Choosing Kokoro or Piper downloads the voice and shows its progress. A failed download returns to the previous voice and says why.
- An import uses the chosen synthesizer. A stalled sentence becomes a second of silence and the book continues.

Tests:
- `BookUiTest.voiceDropDownDefaultsToThePhonesVoiceAndListsAllThree`
- `BookUiTest.importUsesThePhonesVoiceByDefault`
- `BookUiTest.pickingKokoroMakesAnImportUseTheKokoroSynthesizer`
- `BookUiTest.pickingPiperMakesAnImportUseThePiperSynthesizer`
- `BookUiTest.aFailedVoiceDownloadFallsBackToThePreviousVoiceAndSaysWhy`
- `BookUiTest.theOldNaturalVoiceSwitchMeansKokoro`
- `SpeechTest.aStalledSentenceBecomesASecondOfSilenceAndTheBookContinues`
- `RealNeuralVoiceTest.neuralVoiceWritesAWavThatTheImportCanRead`
- `RealSpeechTest.androidEngineWritesAWav`
- `BackgroundPriorityTest.neuralVoiceWorkRunsAtBackgroundPriorityAndHandsThePriorityBack`

Unchecked: how the voices sound. The two tests that use a real engine skip themselves where none exists.

### UC-46 Choose light, dark or system theme, and recognise the app

Goal: read comfortably, and find the app.

- The theme chips switch between light, dark and follow-system. The choice survives recreating the activity, and chips and text stay readable.
- The launcher label is "Screenplay", the icon is adaptive with a monochrome layer, and the shelf is titled with the open-book icon.

Tests:
- `ThemeModeTest.optionsPaneSwitchesBetweenLightDarkAndFollowSystem`
- `ThemeModeTest.themeChipsFollowTheActiveScheme`
- `ThemeModeTest.choiceSurvivesRecreatingTheActivity`
- `ThemeTest.schemeTextIsReadable`
- `AppLabelTest.launcherLabelIsScreenplay`
- `AppIconTest.launcherIconIsAnAdaptiveIconWithMonochromeLayer`
- `LibraryTitleTest.libraryScreenTitleIsAudiobooks`
- `LibraryTitleTest.libraryTitleShowsTheOpenBookIcon`

Unchecked: whether the colours and layout look good to a person. The tests check contrast and structure only.

## Gaps

Use cases with no test:

- UC-03: sharing a PDF or an EPUB to the app with `ACTION_SEND`. The manifest declares the filter and the app reads the stream, but no test sends it.

Behaviour that tests cover only in part, and that someone has to check by hand on a phone:

- OCR quality and speed on the phone's own scans (UC-01, UC-05).
- A real force-quit and a real process kill while a book is made or played (UC-12, UC-18).
- The look of the notification, highlights, theme and layout (UC-19, UC-31, UC-46).
- Real voices, the real Gutendex and Open Library services, and the real Android spell checker (UC-06, UC-13, UC-44, UC-45).
- Exported files in third-party apps (UC-35, UC-38, UC-40).
