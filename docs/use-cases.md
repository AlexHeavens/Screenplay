# Use cases

This document lists what Screenplay lets a listener do, one behaviour at a time. A parent entry (`UC-12`) states a goal. Its children (`UC-12a`, `UC-12b`) are the variations and the error cases of that goal. Each entry has an id, a goal in the listener's words, the behaviour in a few lines, and the tests that prove it.

A test is named `ClassName.methodName`. Unit tests live in `app/src/test` and device tests in `app/src/androidTest`. Device tests marked `@Smoke` are the always-run set.

`scripts/check-use-cases.sh` reads this file. It fails when an id appears twice, when an entry that has no children has no `Tests:` line, or when a named test no longer exists. It warns about every test that no entry names. `./gradlew testDebugUnitTest` runs it. When you rename or delete a test, update the entry in the same change.

`Tests: none yet` means no test proves the behaviour. These entries are collected under [Gaps](#gaps). A line starting with `Unchecked:` names behaviour that no test verifies on a real device. Messages in quotation marks are the exact text the listener sees.

## Contents

- [Importing](#importing): UC-01 to UC-13 (see also [Making a book](#making-a-book))
- [Making a book](#making-a-book): UC-48, UC-49, UC-51
- [Listening](#listening): UC-14 to UC-20
- [Reading along](#reading-along): UC-21 to UC-26, UC-53
- [Notes and highlights](#notes-and-highlights): UC-27 to UC-33
- [Library](#library): UC-34 to UC-40
- [Settings and looks](#settings-and-looks): UC-41 to UC-47
- [Maintainers](#maintainers): UC-50
- [Unsupported or known limits](#unsupported-or-known-limits)
- [Gaps](#gaps)

## Importing

### UC-01 Make an audiobook from a PDF on the shelf

Goal: turn a PDF on my phone into an audiobook with a transcript.

- Tap "Import" on the shelf and pick a file. A PDF or an EPUB becomes a new book; a zip from the share menu or from Export backup is restored (UC-36).
- The app reads each page with on-device OCR, speaks the text, and shows the book as ready.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp`

Unchecked: OCR and speech on the phone itself, with a real scan.

#### UC-01a The shelf shows the file name as the title

Goal: recognise the book I just added.

- The title is the display name of the file without `.pdf` or `.epub`. The ISBN lookup (UC-06) can replace it later.
- The book appears on the shelf at once, under "Importing", before any page is read.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`

#### UC-01b The transcript opens on the first sentence

Goal: start at the beginning.

- A finished book shows its sentences as paragraphs, and the first one is selected.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`

#### UC-01c A PDF with a contents page gets real chapter titles

Goal: see the chapter names of the book, not "Chapter 1".

- The contents page gives the titles and pages. See UC-25 for how the pages are matched to the text.

Tests:
- `CleanupTest.findsChaptersFromContents`

#### UC-01d A PDF without a contents page gets chapters from its headings

Goal: still have chapters when the scan has no contents page.

- With no contents page, there is no plan. Chapters come from headings found in the text, such as "Chapter 3" or a number followed by a title.

Tests:
- `ChapterEstimatorTest.noContentsPageGivesNoPlan`
- `ChapterFixtureDumpTest.aBookWithoutAContentsPageHasNoPlan`
- `CleanupTest.findsChapters`
- `CleanupTest.findsChaptersFromTheirNumbersAndTitles`
- `CleanupTest.findsALectureHeadingAndItsTitle`

#### UC-01h A chapter title printed in block capitals shows in standard capitals

Goal: read "The Prince's Duties", not "THE PRINCE'S DUTIES", in the chapter list and on the reading page.

- A detected title with no lower-case letter is turned into title case. Small words stay lower case except at the start, Roman numerals and well-known acronyms stay upper case, and "PRINCE'S" becomes "Prince's".
- A title with any lower-case letter stays as it is.
- A book imported before this change keeps its stored titles until it is imported again.

Tests:
- `TitleCaseTest.capitalisesANameAfterAnApostropheInitial`
- `TitleCaseTest.capitalisesASmallWordAfterAColon`
- `TitleCaseTest.capitalisesEachPartOfAHyphenatedWord`
- `TitleCaseTest.capitalisesEachWordOfABlockCapitalTitle`
- `TitleCaseTest.doesNotTakeWordsForRomanNumerals`
- `TitleCaseTest.keepsAcronymsUpperCase`
- `TitleCaseTest.keepsNumbersAndPunctuation`
- `TitleCaseTest.keepsOrdinalsLowerCase`
- `TitleCaseTest.keepsRomanNumeralsUpperCase`
- `TitleCaseTest.keepsSmallWordsLowerCaseExceptAtTheStart`
- `TitleCaseTest.keepsTheLetterOfAnAppendix`
- `TitleCaseTest.leavesMixedCaseTitlesAlone`
- `TitleCaseTest.leavesTitlesWithoutLettersAlone`
- `TitleCaseTest.lowersTheLetterAfterAnApostrophe`
- `EpubUiTest.anEpubWithBlockCapitalChapterTitlesShowsThemInStandardCapitals`

#### UC-01e A PDF with an ISBN gets its title from the catalogue

Goal: see a real title and author, not a file name.

- The ISBN is looked for on the first pages. See UC-06 for the lookup, its failure, and the ISBN formats.

Tests:
- `IsbnImportTest.theShelfShowsTheTitleAndAuthorFoundFromTheIsbn`

#### UC-01f A PDF without an ISBN keeps its file title

Goal: not lose the title I know the book by.

- With no valid ISBN on the first pages, no lookup is made and the file title stays.

Tests:
- `IsbnTest.findsNothingInOrdinaryText`
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`

#### UC-01g Importing the same PDF twice makes two books

Goal: not be blocked by a title I already have.

- Every import gets its own id. A second import of a file with the same title adds a second book beside the first. Nothing is replaced and nothing is refused.

Tests:
- `BookUiTest.importingTheSamePdfTwiceMakesTwoBooksWithTheSameTitle`

### UC-02 Open a PDF or EPUB from another app

Goal: start a book from a file manager or a download without opening Screenplay first.

- Open a PDF or an EPUB with Screenplay ("Open with").
- The book joins the import queue and is made like any other.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`
- `EpubUiTest.importsAnEpubWithItsTitleAuthorChaptersAndText`

#### UC-02a Open a PDF with Screenplay

Goal: make a PDF I opened from a file manager.

- The app reads the file from the address of the open request (`ACTION_VIEW`) and queues it.

Tests:
- `BookUiTest.importedPdfBecomesAnAudiobookWithTranscript`

#### UC-02b Open an EPUB with Screenplay

Goal: make an EPUB I opened from a file manager.

- An EPUB is told from a PDF by its `.epub` name or its MIME type.

Tests:
- `EpubUiTest.importsAnEpubWithItsTitleAuthorChaptersAndText`

### UC-03 Share a PDF or EPUB to Screenplay

Goal: send a book to the app from the share sheet of another app.

- Share a PDF or an EPUB to Screenplay with `ACTION_SEND`.
- The book joins the import queue.

#### UC-03a Share a PDF

Goal: make a PDF that I shared from my files app.

- The app reads the shared stream, adds the book to the shelf, and makes it.

Tests:
- `BookUiTest.aPdfSharedFromAnotherAppJoinsTheShelfAndIsMade`

#### UC-03b Share an EPUB

Goal: make an EPUB that I shared from another app.

- The share path is the same as for a PDF, and the file type is chosen as in UC-02b.

Tests: none yet

#### UC-03c A share with no file adds nothing

Goal: not get a broken book when another app shares text or an empty request.

- A share that carries no stream does nothing. The shelf stays empty and shows "No audiobooks yet. Import a PDF or EPUB."

Tests:
- `BookUiTest.aShareWithNoFileAddsNothingAndTheShelfStaysEmpty`

### UC-04 See why a book cannot be made

Goal: learn why a book failed, instead of seeing a spinner forever.

- A book that cannot be made shows as failed, and the shelf row shows the reason. The failed book stays on the shelf, under "Imported", until I delete it.
- Any failure while making a book, even an unexpected one, becomes a failed book with the message of the failure. The worker does not crash the app.

Tests:
- `BookUiTest.aPdfWithNoTextFailsWithAReason`

#### UC-04a A PDF with no text fails with a reason

Goal: know that the scan could not be read.

- A PDF in which OCR finds no words fails with "No text found in this PDF".

Tests:
- `BookUiTest.aPdfWithNoTextFailsWithAReason`

#### UC-04b An EPUB with no text fails with a reason

Goal: know that the EPUB holds nothing to read.

- An EPUB with no readable text is refused with "This EPUB has no readable text." If the text is lost after cleaning, the book fails with "No text found in this EPUB".

Tests: none yet

#### UC-04c A file that cannot be opened fails with a reason

Goal: not get a crash when the file is gone or unreadable.

- When the stream cannot be read, the book is added as failed with "Could not read the PDF: <message>". For an EPUB the text says "EPUB".
- The message is the system's own. A stream that fails with no message shows "Could not read the PDF: null".

Tests:
- `BookUiTest.aFileThatCannotBeOpenedFailsTheBookWithAReasonInsteadOfCrashing`

#### UC-04d A file that is not a PDF fails with a reason

Goal: be told when I picked the wrong file.

- A text file named `.pdf` is added, and then fails while it is made. The shelf row shows the reason.

Tests:
- `BookUiTest.aFileThatIsNotAPdfFailsWithAReason`

#### UC-04e An encrypted PDF or a PDF with zero pages fails with a reason

Goal: be told when the PDF cannot be opened for reading.

- The Android PDF renderer refuses a password-protected file, and a PDF with no pages gives no text. In both cases the book fails with the message of the failure, or with "No text found in this PDF".

Tests: none yet

Unchecked: the exact message the renderer gives for an encrypted file.

#### UC-04f A voice problem fails the book with a message

Goal: know why speech stopped.

- If the phone has no speech engine, or it refuses the text, the book fails with "No text-to-speech engine available: <why>".
- If the engine changes its sample rate half way through, the book fails with "Speech engine changed sample rate mid-book".
- A voice that is chosen but not downloaded fails with "The <voice> voice is not downloaded", for example "The kokoro voice is not downloaded".

Tests:
- `SpeechErrorTest.anEngineThatChangesItsSampleRateHalfWayFailsTheBookWithAMessage`

#### UC-04g A stalled sentence does not fail the book

Goal: finish a book although the voice hangs on one sentence.

- A sentence that gets no answer from the engine for 20 seconds is tried once more. If that stalls too, the sentence becomes one second of silence and the book goes on.
- A stall on the very first sentence still fails the book, because the sample rate is not known yet.

Tests:
- `SpeechTest.aStalledSentenceBecomesASecondOfSilenceAndTheBookContinues`
- `SpeechTest.aStallOnTheFirstSentenceStillFailsBecauseTheSampleRateIsUnknown`

#### UC-04h A book with a lot of audio does not run out of memory

Goal: make a long book on a phone with little memory.

- The audio is written to disk in pieces, so a book with more audio than the heap still renders.

Tests:
- `SpeechTest.aBookWithMoreAudioThanTheHeapStillRenders`

#### UC-04i Speech files that are not mono WAV are refused

Goal: get a clear failure if an engine writes a file the app cannot use.

- A file that is not a WAV, a stereo WAV, and a WAV with no audio are each refused with their own message.

Tests:
- `SpeechErrorTest.aFileThatIsNotAWavIsRefused`
- `SpeechErrorTest.aStereoWavIsRefusedBecauseSpeechMustBeMono`
- `SpeechErrorTest.aWavWithoutAnyAudioIsRefused`

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
- `OcrFixtureTest.radiantEnergy`
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

#### UC-05a Running heads and repeated blocks are dropped

Goal: not hear the book title at the top of every page.

- Lines in the edge band of a page are dropped, and short blocks that repeat on many pages are dropped.

Tests:
- `TextTest.dropsRepeatedShortBlocks`
- `LayoutTest.dropsRunningHeadsBelowTheEdgeBand`

#### UC-05b Footnotes and footnote numbers are dropped

Goal: not hear a footnote in the middle of a sentence.

- Lines in smaller type below the body are dropped. A footnote number that OCR glued to a word, a closing quote, a title or a second quote is stripped.
- A number that belongs to the text, such as a decimal or a number after a quote that text follows, stays.

Tests:
- `LayoutTest.dropsFootnotesInSmallerTypeBelowTheBody`
- `CleanupTest.stripsAFootnoteNumberAfterTheQuoteEndingAParagraph`
- `CleanupTest.stripsAFootnoteNumberOcrReadsAsASecondQuote`
- `CleanupTest.stripsFootnoteNumbersAfterClosingQuotes`
- `CleanupTest.stripsFootnoteSymbolsAfterATitleInCapitals`
- `CleanupTest.keepsANumberAfterAQuoteWhenTextFollows`
- `CleanupTest.keepsRealNumbersAfterQuotesAndDecimals`

#### UC-05c Contents lists are not spoken

Goal: not hear a table of contents read out.

- A run of chapter numbers is a contents list, not chapters. Entries with page numbers, entries joined in one paragraph and cut at the page end are dropped. A chapter heading that the contents page already printed is kept as a heading.
- Prose that only cites a few pages stays.

Tests:
- `CleanupTest.aRunOfChapterNumbersIsAContentsListNotChapters`
- `CleanupTest.dropsContentsEntriesJoinedIntoOneParagraphCutAtThePageEnd`
- `CleanupTest.keepsAChapterHeadingThatTheContentsPageAlreadyPrinted`
- `CleanupTest.keepsProseThatCitesAFewPages`
- `TextStreamTest.theContentsListIsTheOneTheWholeBookGives`

#### UC-05d Index pages are dropped

Goal: not hear the index at the back of the book.

- Pages that look like an index, with many short entries ending in page numbers, are dropped.

Tests:
- `LayoutTest.dropsIndexPages`

#### UC-05e Words and paragraphs cut by a line or page are joined

Goal: not hear a word in two halves.

- A hyphenated word at a line end or a page end is joined. A paragraph cut by a page break is merged. A lone line that carries over to the top of the next page stays with its paragraph. A short last line of body text is kept.
- A drop cap that OCR returns after its paragraph is joined to it. A first letter that was split off is rejoined.

Tests:
- `LayoutTest.joinsLinesAndHyphenatedWords`
- `LayoutTest.keepsALowercaseLineCarriedOverToThePageTop`
- `LayoutTest.keepsAShortLastLineOfBody`
- `LayoutTest.joinsADropCapReturnedAfterItsParagraph`
- `TextTest.rejoinsAWordHyphenatedAcrossPages`
- `TextTest.mergesParagraphCutByPageBreak`
- `TextTest.stripsPageNumberGluedToTheStartOfAContinuation`
- `CleanupTest.rejoinsASplitFirstLetter`

#### UC-05f A chapter heading low on the page is kept

Goal: not lose a chapter that starts part way down a page.

- A heading lower down a page is not taken for a running head. A lone letter beside ordinary text is left alone.

Tests:
- `LayoutTest.keepsAChapterHeadingLowerDown`
- `LayoutTest.leavesALoneLetterBesideOrdinaryText`

#### UC-05g Sentences are cut at sensible places

Goal: hear natural pauses, with no very long runs.

- Text is split into sentences. An overlong sentence is cut. A split after an abbreviation or before a lower-case word is merged back ("Merge split sentences", UC-41).
- Counting words counts any whitespace as a separator, and the sentence at a position is found by its index.
- The pronoun "I" that OCR read as "T" is repaired. A real letter T, such as in "T-shirt" or an initial, is kept.

Tests:
- `TextTest.splitsSentences`
- `TextTest.cutsOverlongSentences`
- `TextTest.countsWordsSeparatedByAnyWhitespace`
- `TextTest.indexAtFindsCurrentSentence`
- `TextTest.keepsParagraphsThatEndInAFullStop`
- `TextTest.keepsALetterTThatIsNotThePronoun`
- `TextTest.fixesThePronounIOcrReadsAsT`
- `CleanupTest.mergesAfterAbbreviationAndBeforeLowercase`
- `CleanupTest.keepsShortSentencesAndParagraphsEndingInAYearAmongHeadings`

#### UC-05h A contents page is shown, not spoken

Goal: see the book's contents page in the reader without hearing it read.

- The option "Contents pages shown, not spoken" is on by default. It applies to books made from now on.
- A contents page is found from its text alone, in the first 15 pages (or EPUB files). A scanned PDF is read after OCR. An EPUB is read as paragraphs.
- Each line has a shape. An entry is a short line that ends in a page number or Roman numeral (after dot leaders), or starts with "Chapter", a number or a Roman numeral.
- A page is contents when it has the heading "Contents" or "Table of Contents" and at least 3 entries. The heading alone is not enough, so an index or a list of illustrations stays text.
- Only a page with the heading counts. A route for the next page of a long list, with no heading, is built and tested but off (`ContentsPages.HEADLESS`), so a list that runs onto a second page has its later pages spoken.
- The contents is kept as a note under the first paragraph, in small type, with no mark. It is not in the transcript, is not spoken and is not counted in the progress.
- With the option off, the contents page is spoken as before.
- Measured on saved scans with the headingless route on: all 10 contents pages of two books were found and no other front page was. With it off, only the pages that carry the heading are found. A contents list that sits after page 15 or has neither a heading nor page numbers is missed.

Tests:
- `ContentsPagesTest.aHeadingAndEntriesWithPageNumbersIsAContentsPage`
- `ContentsPagesTest.aHeadingWithTooFewEntriesIsNotEnough`
- `ContentsPagesTest.anIndexOrAListOfIllustrationsHasNoContentsHeading`
- `ContentsPagesTest.aPageWithoutAHeadingThatFollowsAContentsPageIsItsContinuation`
- `ContentsPagesTest.aPageWithoutAHeadingIsNotContentsUnlessThePageBeforeWas`
- `ContentsPagesTest.theHeadinglessRouteIsOffByDefault`
- `ContentsPagesTest.anIndexPageContinuedIsNotAContentsPageEvenAfterOne`
- `ContentsPagesTest.aNormalChapterPageIsNotContents`
- `ContentsPagesTest.aListInAFileOfProseEndsWhereTheProseStarts`
- `ContentsPagesTest.aListThatFollowsAContentsPageButIsHeadedByAnotherTitleIsNotItsContinuation`
- `ContentsPagesTest.aPageThatOnlyStartsWithAContentsListIsAPageOfText`
- `ContentsPagesTest.entriesHaveTheShapeOfALineThatEndsInAPageOrOpensWithALabel`
- `ContentsPagesTest.entriesWithoutPageNumbersAreFoundByTheirChapterLabelsAndTheTitlesAmongThem`
- `ContentsPagesTest.theHeadingIsReadThroughOcrPunctuationCaseAndARunningHeadsPageNumber`
- `ContentsPagesTest.findsEveryContentsPageOfTwoRealScansAndNoOtherFrontPage`
- `ContentsPagesTest.noBodyPageOfTheSavedScansIsContents`
- `EpubTest.aContentsListInTheMiddleOfAFileIsTakenOutOfTheTextAndKeptAsANote`
- `EpubTest.withTheContentsOptionOffAContentsListIsReadAsBefore`
- `EpubTest.aFileOfTheBookThatMerelyHasAHeadingCalledContentsIsLeftAlone`
- `EpubTest.aNavEntryThatPointsAtTheContentsListDoesNotMakeAChapterNamedContents`
- `ContentsUiTest.aContentsPageIsShownInTheReaderButNeverSpokenOrInTheTranscript`
- `ContentsUiTest.withTheOptionOffTheContentsPageIsSpokenAsBefore`
- `ContentsUiTest.theContentsOptionIsOnByDefaultAndSavedWithTheOthers`

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

#### UC-06a An ISBN after its label is found

Goal: have the usual printed form read.

- An ISBN-13 with hyphens or spaces, or without them, is found after "ISBN". An ISBN-10 with an X as its check digit is found.

Tests:
- `IsbnTest.findsAHyphenatedIsbn13AfterItsLabel`
- `IsbnTest.findsAnIsbn13WithoutHyphensOrWithSpaces`
- `IsbnTest.findsAnIsbn10WithACheckDigitX`

#### UC-06b An ISBN that OCR lost the label of is found

Goal: still get the title when the word "ISBN" was misread.

- A bare 13-digit number with a valid check digit is taken as an ISBN.

Tests:
- `IsbnTest.findsABare13DigitIsbnWhenOcrLostTheLabel`

#### UC-06c A number that is not an ISBN is ignored

Goal: not look up the wrong book.

- A number with a bad check digit is skipped, and the next valid one is taken. Numbers after the ISBN, such as a year, are ignored.

Tests:
- `IsbnTest.rejectsABadCheckDigit`
- `IsbnTest.skipsABadIsbnAndTakesTheNextValidOne`
- `IsbnTest.ignoresNumbersAfterTheIsbnSuchAsAYear`

#### UC-06d An empty or failed reply keeps the file title

Goal: not lose the book because the catalogue is down.

- An empty reply means no book. A failed request keeps the file title, and the book still finishes.

Tests:
- `IsbnTest.anEmptyReplyIsNoBook`
- `IsbnImportTest.aFailedLookupKeepsTheFileTitleAndTheBookStillFinishes`

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

#### UC-07a The shelf row says what the book is doing

Goal: know how far the book has got.

- A book being read shows "Reading <n>%". A book being spoken shows "Speaking <n>%". Every later phase shows the earlier ones as complete.

Tests:
- `BookLabelTest.readingShowsItsPercentageAndTheWordsSpokenSoFar`
- `BookLabelTest.everyLaterPhaseShowsTheEarlierOnesAsComplete`

#### UC-07b Speaking progress is scaled by the pages read

Goal: see honest progress while the length is still unknown.

- While pages are still being read, speaking progress is the share of words spoken, scaled by the share of pages read. Once the last page is read, it is the words spoken of the total.
- A book with nothing read shows no progress. Only a finished or reading book can be opened.

Tests:
- `BookProgressTest.speechProgressIsTheSpokenShareOfTheWordsReadScaledByThePagesRead`
- `BookProgressTest.onceTheLastPageIsReadSpeechProgressIsWordsSpokenOfTheTotal`
- `BookProgressTest.aBookWithNothingReadHasNoProgressAndOnlyAFinishedOrReadingBookOpens`

#### UC-07c Speech can start on page one after a preface

Goal: hear the first chapter as soon as it exists.

- Speech starts once the preface is followed by prose. A late heading waits until the book is long enough to count it.

Tests:
- `TextStreamTest.speechCanStartOnPageOneOnceThePrefaceIsFollowedByProse`
- `TextStreamTest.aLateHeadingWaitsUntilTheBookIsLongEnoughToCountIt`

#### UC-07d Audio is available while more sentences are still to come

Goal: not wait for the last sentence.

- Several engines speak at once, and sentences are still written in order. The audio so far is reported while more sentences remain.

Tests:
- `SpeechTest.parallelEnginesStillWriteSentencesInOrderAndReportChunks`
- `SpeechTest.audioIsReportedWhileMoreSentencesAreStillToCome`

#### UC-07e A book still being made plays at a scaled position

Goal: have the position bar make sense before the audio is whole.

- For a book still being made, the audio length so far is scaled by the share spoken.

Tests:
- `PositionTest.aBookStillBeingMadeScalesTheAudioSoFarByTheShareSpoken`

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

#### UC-08a An EPUB with a table of contents gets its chapters from it

Goal: see the chapters the author named.

- An EPUB 3 uses its nav document, with nested chapters. An EPUB 2 uses its NCX, including an anchor inside a file.
- Each chapter heading is marked by its own sentence, not by its contents-list entry.

Tests:
- `EpubTest.readsTheTitleAuthorParagraphsAndNestedChapters`
- `EpubTest.anEpub2UsesItsNcxAndAnAnchorInsideAFile`
- `EpubTest.marksNameEachChapterHeadingByItsSentenceNotItsContentsListEntry`

#### UC-08b An EPUB without a table of contents gets its chapters from its headings

Goal: still get chapters from a plain EPUB.

- With no nav and no NCX, chapters come from the headings in the text.

Tests:
- `EpubTest.anEpubWithNoNavGetsItsChaptersFromTheHeadings`

#### UC-08c Several authors and the spine order

Goal: have the right author line and the right reading order.

- Several authors are joined into one line. The reading order is the spine, not the order of the manifest.

Tests:
- `EpubTest.joinsSeveralAuthorsAndFollowsTheSpineNotTheManifest`

#### UC-08d Tables, notes and entities are kept, scripts and images are not

Goal: hear the text of the page, and nothing that is not text.

- The text of tables, notes and character entities is kept. Scripts and images are left out.

Tests:
- `EpubTest.keepsTheTextOfTablesNotesAndEntitiesButNotScriptsOrImages`

#### UC-08e An EPUB needs no PDF to be backed up

Goal: back up an EPUB book like any other.

- A backup of an EPUB book holds the EPUB, and the restore needs no PDF.

Tests:
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

#### UC-09a An EPUB with DRM is refused

Goal: know the file is protected.

- The book fails with "This EPUB is protected by DRM, so it cannot be imported."

Tests:
- `EpubUiTest.saysWhyAnEpubWithDrmCannotBeImported`
- `EpubTest.aDrmEpubIsRefusedWithAClearMessage`

#### UC-09b A file that is not an EPUB is refused

Goal: know that the file is not a book.

- A file that is not a zip gives "This is not an EPUB: the file is not a zip archive." A zip without `META-INF/container.xml` gives "This is not an EPUB: META-INF/container.xml is missing."

Tests:
- `EpubTest.malformedFilesAreRefusedNotCrashedOn`

#### UC-09c A damaged EPUB is refused

Goal: know that the file is broken, not that the app is.

- A container that names no package, a missing package file, a file that is too large, and XML that is not valid each give "This EPUB is damaged: <detail>".

Tests:
- `EpubTest.malformedFilesAreRefusedNotCrashedOn`

#### UC-09d Obfuscated fonts are not DRM

Goal: import a book that hides its font files but is not protected.

- Only encryption that is not font obfuscation counts as DRM.

Tests:
- `EpubTest.anObfuscatedFontIsNotDrm`

### UC-10 Queue several books and have them made one at a time

Goal: pick many books and leave the phone to work.

- Books are made in queue order, one at a time. The book being made shows under "Importing".
- A failed book leaves the queue and the next is made.
- When all are made, the section goes and the books sit under "Imported".

Tests:
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp`
- `ImportQueueTest.booksAreMadeOneAtATimeInQueueOrder`
- `ImportQueueTest.aFailedBookLeavesTheQueueAndTheNextIsMade`
- `ImportQueueTest.aWaitingBookIsNotMadeWhileAnotherIsHeld`
- `ImportQueueTest.anEmptyQueueHasNothingImportingSoTheShelfShowsNoImportingSection`
- `ImportQueueTest.aWaitingBookSaysNothingAndTheBookBeingMadeHasItsStages`

#### UC-10a The queue lists the running book first

Goal: see what is made now and what is next.

- The book being made comes first, then the waiting books in their order. Each row shows its place.

Tests:
- `ImportQueueTest.theBookBeingMadeComesFirstThenTheWaitingOnesInTheirOrder`
- `ImportQueueTest.aWaitingBookSaysNothingAndTheBookBeingMadeHasItsStages`

#### UC-10b An empty queue shows no Importing section

Goal: have a clean shelf when nothing is being made.

- With nothing queued or running, the "Importing" section is not shown.

Tests:
- `ImportQueueTest.anEmptyQueueHasNothingImportingSoTheShelfShowsNoImportingSection`

#### UC-10c A book that ends unfinished is not tried again at once

Goal: not loop on a book that keeps stopping.

- A book that ends without being ready or failed is not picked again in the same run.

Tests:
- `ImportQueueTest.aBookThatEndsUnfinishedIsNotTriedAgainInTheSameRun`

#### UC-10d A failed book does not hold up the others

Goal: have the rest of my books made.

- A failed book leaves the queue, and the next book is made. The failed book stays on the shelf with its reason.

Tests:
- `ImportQueueTest.aFailedBookLeavesTheQueueAndTheNextIsMade`

#### UC-10e See which book is made and pause it from the notification or the shelf

Goal: know what the import is doing without opening the app, and stop it when I need the phone's power or network.

- The "Making an audiobook" notification shows the title of the book being made and its stage with percent, such as "Reading 41%". It changes when the queue moves to the next book or is reordered.
- Its Pause button stops the book where it is. The book keeps its stage and progress, and the shelf shows "Paused" on it. Nothing else is made while paused.
- While paused the notification says "Paused" with the stage, and its button is Resume. Resume carries on at the stage and progress the book had.
- The shelf's "Importing" section has the same Pause and Resume button. It uses the same pause as the notification, so the shelf, the notification and the queue rows always show the same state.

Tests:
- `ImportNoticeTest.theNoticeNamesTheBookBeingMadeAndItsStageWithPercent`
- `ImportNoticeTest.speakingShowsTheWordsShare`
- `ImportNoticeTest.followsTheQueueWhenTheNextBookTakesOver`
- `ImportNoticeTest.aWaitingBookIsNamedWhenNoneRuns`
- `ImportNoticeTest.pausedSaysSoAndKeepsTheStage`
- `ImportNoticeTest.anEmptyQueueHasTheGeneralTitle`
- `ImportNoticeTest.theShelfLabelsTheRunningBookPausedOnlyWhilePaused`
- `ImportQueueUiTest.theNotificationNamesTheBookAndItsStageAndPausesAndResumesItThroughTheRealWorker`
- `ImportQueueUiTest.theHomeScreenPauseAndResumeShareTheNotificationsPausedState`

### UC-11 Reorder the import queue

Goal: have the book I want next made next.

- Every book in the queue has a drag handle, the book being made too. Holding the handle starts the drag at once; a long press on the row works as well.
- While a book is held, a line on the edge of the row it would sit against shows where it lands when released; before it moves, the line is above its own row. There is no "move up" button.
- Whatever is dropped first is made first. A book being made that is passed, or dragged down, is stopped and waits in its new place.
- A stopped book keeps the speech it made: when it is made again it carries on after it.

Tests:
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp`
- `ImportQueueTest.theDropPlaceIsTheRowsDraggedAwayAndStaysInsideTheLine`
- `ImportQueueTest.movingUpWorksWhenBooksShareAnOrder`
- `ImportQueueTest.aWaitingBookDroppedBetweenWaitingBooksTakesThatPlaceAndTheRunningBookKeepsRunning`
- `ImportQueueTest.aBookDroppedFirstIsMadeFirstAndTheRunningBookItPassedIsStoppedAndWaitsNextInLine`
- `ImportQueueTest.theRunningBookDraggedDownIsStoppedAndTheBookItPassedIsMadeInsteadOfIt`
- `ImportQueueTest.aBookStoppedToLetAnotherGoFirstIsMadeAgainInTheSameRun`
- `SpeechTest.aStoppedBookCarriesOnFromItsKeptSpeechWithoutSpeakingItAgain`
- `SpeechTest.keptSpeechIsOnlyWhatIsWholeInTheAudioOnDisk`

#### UC-11a See where a dragged book will land

Goal: drop a book in the right place the first time.

- A line on the edge of the row the held book would sit against shows its landing place. Before it moves, the line is above its own row. There is no "Move up" button.
- Moving a book up by one place works when books share an order value.

Tests:
- `ImportQueueTest.theDropPlaceIsTheRowsDraggedAwayAndStaysInsideTheLine`
- `ImportQueueTest.movingUpWorksWhenBooksShareAnOrder`
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp`

#### UC-11b Drag a waiting book to a new place

Goal: put a book exactly where I want it.

- A waiting book dropped between two waiting books takes that place, and the running book keeps running.

Tests:
- `ImportQueueTest.aWaitingBookDroppedBetweenWaitingBooksTakesThatPlaceAndTheRunningBookKeepsRunning`

#### UC-11c Pass or drag down the running book

Goal: change my mind about which book comes first.

- A book dropped first is made first, and the running book it passed is stopped and waits next in line. Dragging the running book down stops it and makes the book it passed instead.
- A stopped book is made again in the same run.

Tests:
- `ImportQueueTest.aBookDroppedFirstIsMadeFirstAndTheRunningBookItPassedIsStoppedAndWaitsNextInLine`
- `ImportQueueTest.theRunningBookDraggedDownIsStoppedAndTheBookItPassedIsMadeInsteadOfIt`
- `ImportQueueTest.aBookStoppedToLetAnotherGoFirstIsMadeAgainInTheSameRun`

#### UC-11d A stopped book keeps its speech

Goal: not lose work when a book is put back.

- Only speech that is whole in the audio on disk is kept. The book carries on after it, and does not speak it again.

Tests:
- `SpeechTest.aStoppedBookCarriesOnFromItsKeptSpeechWithoutSpeakingItAgain`
- `SpeechTest.keptSpeechIsOnlyWhatIsWholeInTheAudioOnDisk`

#### UC-11e A moved book shows its new place at once

Goal: see my drop take effect, with no wait.

- When a book is dropped, the shelf lists the new order in the same frame, even while the running book it passed is still stopping and the move is still being saved.
- Saving the move and stopping the running book happen in the background, so the shelf does not freeze. The rows do not return to the old order while the passed book stops.
- A book that is being fetched ahead is not restarted or lost by a move, and its progress stays.
- A book stopped by a move after its text was read carries on as Speaking with the words it had: its row never goes back through Reading.

Tests:
- `ImportQueueUiTest.aBookDroppedFirstIsListedFirstAtOnceWhileTheMoveIsStillBeingSaved`
- `ImportQueueUiTest.aBookStoppedByAMoveCarriesOnAtItsStageAndNeverGoesThroughReadingAgain`
- `ImportQueueTest.aMovedBookIsListedInItsNewPlaceAtOnceWithoutWaitingForTheBookItPassedToStop`
- `ImportQueueTest.aBookToldToStopIsShownWaitingUntilItsWorkerHasStopped`
- `ImportQueueTest.aReorderChangesWhichBookIsFetchedNext`

#### UC-11f A move that cannot be saved is undone

Goal: never see an order that is not the real one.

- When the move cannot be saved, the shelf shows the old order again and a message says why. The running book carries on.

Tests:
- `ImportQueueUiTest.aMoveThatCannotBeSavedPutsTheOldOrderBackAndSaysWhy`

### UC-12 Keep making books after the app restarts

Goal: not lose the queue when the app or phone stops.

- After a restart, the interrupted book is made again before the waiting ones.

Tests:
- `ImportQueueTest.aRestartMakesTheInterruptedBookAgainBeforeTheWaitingOnes`

Unchecked: a real process kill, and the foreground job that keeps a long book going when the app is closed.

#### UC-12a Speech made before a kill is kept or redone from scratch

Goal: finish a book that was cut short.

- If the speech made so far matches the book, the worker carries on after it. If it does not match (the worker raises "the speech made so far does not match the book" internally), it speaks the book again from the start.

Tests:
- `SpeechTest.keptSpeechIsOnlyWhatIsWholeInTheAudioOnDisk`

#### UC-12b Making a book shows a notification

Goal: see that the phone is still working on my books.

- While a book is made, a low-importance "Audiobook generation" notification titled "Making an audiobook" keeps the job alive in the foreground.

Tests: none yet

Unchecked: the notification itself and a long book that survives closing the app.

### UC-13 Find a public-domain book and add it to the queue

Goal: get a book without hunting for a file.

- "Catalogue" on the shelf searches a list of Project Gutenberg's public-domain text books that is stored on the phone. A search answers at once, with no network request, because the list is searched on the device (UC-13j).
- The list is shown as soon as the screen opens, in title order, and narrows as the reader types or picks a genre. It says how many books match.
- Tapping a book adds it to the import queue at once, as an EPUB still to fetch. The EPUB is fetched from gutenberg.org when the book's turn comes, never at the tap.
- While one book is made (compute bound), the highest book in line that has no file yet is fetched in the background (network bound), so it is ready when its turn comes. Its row says "Downloading 42% · 3.1 of 7.4 MB" under a bar that fills with the bytes received over the length the server announced, then "Queued". With no announced length the bar runs without end and the row says the bytes so far. The result in "Catalogue" shows the same text. Progress is kept in memory only and updates once per whole percent, so it costs no disk writes.
- One fetch runs at a time, in queue order, and a reorder, an addition or a deletion is taken into account at the next fetch. A book is never fetched twice.
- A failed fetch shows its error on the row and does not stop the book being made; the book's own turn retries it, and fails it with the reason if that fails too.
- A fetch writes to a temporary name and renames it when whole, so a stopped worker or a killed app never leaves a partial file taken for a book.

Tests:
- `FindBooksUiTest.searchesByTitleGenreAndSortThenAddsTheTappedBookToTheImportQueue`
- `FindBooksUiTest.theSecondBookIsDownloadedWhileTheFirstIsBeingMade`
- `ImportQueueTest.theHighestWaitingBookWithoutItsFileIsFetchedNext`
- `ImportQueueTest.fetchedBooksFileBooksAndTheBookBeingMadeAreSkipped`
- `ImportQueueTest.aReorderChangesWhichBookIsFetchedNext`
- `ImportQueueTest.theRowSaysNothingWhileItsFileIsFetchedAndShowsAFailure`
- `GutenbergTest.theProjectGutenbergLicenceSurvivesReadingAndWritingTheBook`

Unchecked: the real GitHub release and gutenberg.org, and a fetch cut short by a killed process. The tests use a fake network.

#### UC-13a Search by title or author

Goal: find a book I know.

- The search box ("Title or author") keeps the books where every word is in the title or an author's name. Case and accents are ignored: "cafe" finds "Café".
- Authors are shown in reading order ("Charles Dickens"); several are joined with " & ". A book with no author shows "Unknown author".
- "No books found." is shown when nothing matches.

Tests:
- `CatalogueTest.everyWordMustMatchTheTitleOrAnAuthorIgnoringCaseAndAccents`
- `CatalogueTest.aRowShowsTheTitleTheAuthorsInReadingOrderTheLanguagesAndTheDownloads`
- `FindBooksUiTest.searchesByTitleGenreAndSortThenAddsTheTappedBookToTheImportQueue`

#### UC-13b Choose one of nine genres

Goal: browse by kind of book.

- The "Genre" chips offer nine genres. A genre keeps the books with a bookshelf or subject that contains the genre's word, ignoring case. Picking the chosen genre again clears it.

Tests:
- `CatalogueTest.everyGenreIsOneOfTheNineOffered`
- `CatalogueTest.aGenreMatchesTheBooksBookshelvesAndSubjects`
- `FindBooksUiTest.searchesByTitleGenreAndSortThenAddsTheTappedBookToTheImportQueue`

#### UC-13c Sort by title, downloads or rating

Goal: see the books people read most or like best, or find one by its title.

- The "Sort by" chips offer "Title A–Z" (the default, ignoring case and accents), "Most downloaded" and "Best rated". Gutenberg publishes no ratings, so the download count is its only measure. Books with equal downloads keep the order of their numbers.
- "Best rated" uses the average rating Open Library readers gave the book (UC-13l). A book with at least 5 ratings is ranked by its average, then by how many ratings it has. Books with fewer ratings, or none, follow by downloads, so one 5-star vote cannot put a book first.
- The sort applies to the books that match the search and the genre. The list shows 50 books and loads 50 more when the reader scrolls near its end, while others match. There is no "More results" button.

Tests:
- `CatalogueTest.theDefaultOrderIsByTitleAndTheDownloadsSortIsMostDownloadedFirst`
- `CatalogueTest.theRatingSortRanksByAverageAndLeavesBooksWithFewRatingsToTheEndByDownloads`
- `CatalogueTest.theResultCarriesTheFirstBooksAndHowManyMatchedInAll`
- `FindBooksUiTest.scrollingToTheEndOfTheListLoadsMoreBooksWithoutAButton`
- `FindBooksUiTest.searchesByTitleGenreAndSortThenAddsTheTappedBookToTheImportQueue`
- `FindBooksUiTest.sortsByRatingWithTheFewlyRatedLastAndShowsTheRatingOnTheRow`

#### UC-13d English books only

Goal: see only books I can read.

- The "English only" switch is on by default. A book in English and another language counts as English. The row shows the author, the languages in capitals, the downloads and, "★ 4.5 (20)" (the average and the number of ratings) when Open Library has ratings for the book, and "Not rated" when it has none.

Tests:
- `CatalogueTest.englishOnlyLeavesOutABookWithNoEnglishAndTheSwitchTurnsItOff`
- `FindBooksUiTest.searchesByTitleGenreAndSortThenAddsTheTappedBookToTheImportQueue`

#### UC-13e Only books that can be added are listed

Goal: not add a book that cannot be read.

- The list holds only text books that are public domain in the USA and have an EPUB: the daily build leaves out copyrighted books, audio, scores and books with no EPUB (UC-13k). The app fetches only the `https://www.gutenberg.org/ebooks/<id>.epub3.images` link for a book it lists.
- The Project Gutenberg licence text stays with the book when it is read and written.

Tests:
- `CatalogueTest.readsTheVersionAndEveryBookMostDownloadedFirst`
- `GutenbergTest.theProjectGutenbergLicenceSurvivesReadingAndWritingTheBook`

#### UC-13f Tapping a book adds it to the queue

Goal: add a book with one tap and carry on browsing.

- The book is added as an EPUB still to fetch, and the screen says "Added to the import queue".

Tests:
- `FindBooksUiTest.searchesByTitleGenreAndSortThenAddsTheTappedBookToTheImportQueue`

#### UC-13g A book is fetched while another is made

Goal: have the next book ready when its turn comes.

- The highest waiting book with no file is fetched next. Fetched books, file books and the book being made are skipped. A reorder changes which is fetched next. The row says "Downloading…" while it is fetched.

Tests:
- `FindBooksUiTest.theSecondBookIsDownloadedWhileTheFirstIsBeingMade`
- `FindBooksUiTest.aBookDownloadedOnItsOwnTurnShowsItsProgressOnItsResultAndItsRowAndThenCarriesOn`
- `FindBooksUiTest.aBookDownloadedAheadShowsItsProgressWhileTheBookBeingMadeShowsNoneAndReturnsToQueued`
- `FindBooksUiTest.aDownloadOfUnknownLengthShowsAnIndeterminateBarAndTheBytesSoFar`
- `DownloadProgressTest.aKnownLengthGivesAFractionAndAPercentWithBothSizes`
- `DownloadProgressTest.anUnknownLengthGivesNoFractionAndOnlyTheBytesReceived`
- `DownloadProgressTest.theFractionAndPercentStayWithinTheBarWhenTheServerSendsMoreThanItAnnounced`
- `DownloadProgressTest.theThrottleHandlesAnUnknownLengthByTenthsOfAMegabyte`
- `DownloadProgressTest.theThrottlePassesOnlyReportsThatChangeWhatTheReaderSees`
- `GutenbergTest.aDownloadReportsTheBytesReceivedAndTheAnnouncedLengthAndKeepsTheFileOnlyWhenWhole`
- `ImportQueueTest.theHighestWaitingBookWithoutItsFileIsFetchedNext`
- `ImportQueueTest.fetchedBooksFileBooksAndTheBookBeingMadeAreSkipped`
- `ImportQueueTest.aReorderChangesWhichBookIsFetchedNext`
- `ImportQueueTest.theRowSaysNothingWhileItsFileIsFetchedAndShowsAFailure`

#### UC-13h A failed download shows its error and leaves no partial file

Goal: see why a book did not download, and not keep a broken file.

- A failed fetch shows its error on the row. The file is written under a `.part` name and renamed when whole, so a failure leaves no file that looks like a book.

Tests:
- `ImportQueueTest.theRowSaysNothingWhileItsFileIsFetchedAndShowsAFailure`
- `GutenbergTest.aFailedDownloadLeavesNoFileAndAnEpubIsOnlyEverTheGutenbergLinkForTheBookId`

#### UC-13i The book list cannot be downloaded

Goal: be told what is wrong, in words, and try again.

- With no stored list and no connection, the screen says "Could not reach the book catalogue. Check the connection and try again." and offers "Try again".
- Too many requests (status 429): the message says to wait, and the wait follows the server's retry delay. Any other error status is named by its number.
- A downloaded file that is not a book list in a format the app knows is refused with "The book list is not in a form this app can read. Updating the app may fix this."

Tests:
- `FindBooksUiTest.saysWhenTheFirstDownloadFailsAndOffersToTryAgain`
- `CatalogueErrorTest.aCatalogueThatCannotBeReachedSaysToCheckTheConnection`
- `CatalogueErrorTest.tooManyRequestsSaysToWaitAndKeepsTheServersRetryDelay`
- `CatalogueErrorTest.anyOtherErrorStatusIsNamedByItsNumber`
- `CatalogueTest.aFileInAnotherFormatIsRefusedWithAMessageForTheReader`
- `CatalogueTest.aTagNumberOutsideTheTagLineIsRefused`

#### UC-13j The list is stored, searched offline and refreshed daily

Goal: search at once, even with no connection, and be polite to the host.

- The first time "Catalogue" opens it downloads the list in one request (about 2.2 MB, xz) from this project's GitHub release `catalogue`, shows "Downloading the book list" with its progress, and keeps the file. Nothing is downloaded before the screen opens.
- Later it reads the stored file and searches it with no network. A stored list older than a day is replaced when the screen opens; the old one is searched meanwhile.
- A refresh that fails, or that returns something that is not a book list, keeps the old copy and says nothing. A new copy replaces the old one only after it reads correctly.
- After a failure the client waits before it asks again, doubling the wait up to a limit, and honours the server's Retry-After. The user agent names the app, its version, and where to ask about it.

Tests:
- `FindBooksUiTest.searchesTheStoredListWithNoConnectionAfterTheFirstDownload`
- `FindBooksUiTest.aListOlderThanADayIsReplacedAndAFailedOrUnreadableReplacementKeepsTheOldOne`
- `GutenbergTest.waitsAfterAFailureAndDoublesTheWaitUpToALimit`
- `GutenbergTest.followsTheServersRetryAfterButNeverWaitsMoreThanTheLimit`
- `GutenbergTest.theUserAgentNamesTheAppItsVersionAndWhereToAskAboutIt`

#### UC-13k The list is built daily from Gutenberg's bulk catalogue

Goal: keep the list current without calling Gutenberg from every phone.

- A scheduled GitHub Actions workflow (`catalogue.yml`, daily and on demand) runs `scripts/publish-catalogue.sh`. It downloads Gutenberg's one bulk file (`rdf-files.tar.bz2`), reduces it with `scripts/build-catalogue.py` and uploads `catalogue.tsv.xz` to the release `catalogue` with `--clobber`, unless the books are the same as the published ones. The first line of the file names the format and the Gutenberg build date.

Tests:
- `CatalogueTest.readsTheVersionAndEveryBookMostDownloadedFirst`

Unchecked: the scheduled run itself and the reduction of a new feed by an automated test. The first publication was made by running the same script by hand.

#### UC-13l Ratings come from Open Library's monthly dump

Goal: show how readers rated a book without calling Open Library from every phone.

- Open Library publishes a dump each month (`ol_dump_ratings_<date>.txt.gz`, one row per rating, and `ol_dump_editions_<date>.txt.gz`, 12.6 GB). The daily job learns the newest dump date from the redirect of `ol_dump_ratings_latest.txt.gz`, with no download, and compares it with the dump date in the first line of the release asset `ol-ratings.tsv.xz`.
- Only a newer dump is read. The job streams the editions dump in parallel ranges (`scripts/fetch-ranges.py`), keeps the lines that name a Project Gutenberg id, averages each work's ratings and joins them by that id. It also keeps the works with at least 5 ratings and their authors, so that a book without an id match can match by title and author surname (`scripts/build-ratings.py`, `scripts/build-catalogue.py`). It uploads the small result as `ol-ratings.tsv.xz`. Without a newer dump it rebuilds the catalogue from that asset.
- The catalogue file (format 2) gains two columns per book, the average times ten and the rating count; both are empty for a book nobody rated. The app still reads format 1 files, whose books have no rating.
- The screen credits Open Library as the source of the ratings.

Tests:
- `CatalogueTest.aFormat2RowCarriesItsRatingAndAFormat1RowHasNone`
- `CatalogueTest.aRatingOutsideOneToFiveStarsOrWithoutACountIsRefused`
- `scripts/test_build_ratings.py` (id join, title and author match, row safety; run by hand: `cd scripts && python3 -m unittest test_build_ratings`)

Unchecked: the scheduled run on GitHub (CI minutes were used up; the script was run by hand with `DRY_RUN=1`).

## Making a book

### UC-51 Understand what a book needs to be made

Goal: know what the app does to a PDF or an EPUB, and in what order.

- The worker takes the queue head and sets the book to Reading, then Spelling (if on), Speaking and Encoding, and finally Ready. The phases are stored with the book.
- While it works it shows the "Making an audiobook" notification (UC-12b).

#### UC-51a A PDF is read page by page with OCR

Goal: have scanned pages read.

- Each page is rendered at 1600 px wide and read with ML Kit, with 2 to 8 pages in parallel. The layout rules (UC-05) then drop the page furniture.

Tests:
- `OcrBenchmarkTest.stages`

Unchecked: parallel OCR on the phone's own processor.

#### UC-51b A PDF with a text layer is still read with OCR

Goal: know a limit, not be surprised by it.

- The app does not read the text layer of a PDF. A PDF made from a text file takes as long as a scan. See [Unsupported or known limits](#unsupported-or-known-limits).

Tests: none yet

#### UC-51c Spoken audio is encoded to M4A

Goal: get a compact audio file.

- The speech is written as raw audio while the book is made, and encoded to `audio.m4a` at the end. Encoding is at least twenty times faster than real time.

Tests:
- `AacEncoderTest.encodesAtLeastTwentyTimesFasterThanRealTime`

### UC-48 Delete a book while it is made, or empty the queue

Goal: stop a book I no longer want.

- Deleting a book removes it from the queue, cancels its work and deletes its folder. Deleting the running book makes the next book run.
- Deleting every book leaves an empty shelf and no "Importing" section.

#### UC-48a Delete the book being made

Goal: stop a wrong import at once.

- The book is cancelled in the queue, and its folder, with the partial audio, is removed.

Tests: none yet

Unchecked: a delete in the middle of OCR on a real device.

#### UC-48b Delete a waiting book

Goal: take a book out of the queue.

- The book is removed from the queue and never made.

Tests:
- `DeleteConfirmTest.deleteAsksFirstCancelKeepsAndConfirmDeletes`

### UC-49 Open a book whose files are damaged

Goal: not get a crash from a half-written or missing file.

- A transcript that was cut short is unreadable but never a crash, and a missing transcript is empty.
- A reader never sees a half-written transcript, because the file is written whole and then renamed.

#### UC-49a A missing transcript is empty

Goal: open a book that has no transcript yet.

Tests:
- `TranscriptReadTest.missingFileIsEmpty`
- `TranscriptReadTest.readsAWholeTranscript`

#### UC-49b A truncated transcript is unreadable, not a crash

Goal: still use the app after a write was cut short.

- A transcript cut mid-write is treated as unreadable.

Tests:
- `TranscriptReadTest.aTruncatedTranscriptIsUnreadableNotACrash`

#### UC-49c A reader never sees a half-written transcript

Goal: not see a book with half its text.

Tests:
- `TranscriptReadTest.aReaderNeverSeesAHalfWrittenTranscript`

#### UC-49d A book with a damaged details file

Goal: not lose the whole shelf because of one book.

- The details file (`meta.json`) of a book is read without a guard. A damaged one throws while the shelf refreshes. This is a bug. See [Gaps](#gaps).

Tests: none yet

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

#### UC-14a Play is grey until speech exists

Goal: not press Play on a book that has nothing to hear.

- Play is disabled until the first audio exists. See UC-07.

Tests:
- `ImportProgressTest.readerGreysOutPlayUntilSpeechExistsAndShowsPageAndSpeechProgressWhileImporting`

#### UC-14b Playback catches up with a book still being made

Goal: listen at the edge of a growing book.

- When playback reaches the end of the audio made so far, it waits and carries on as more audio is written. Stopping at that moment does not crash.

Tests:
- `LivePlayerTest.stoppingPlaybackThatHasCaughtUpWithTheAudioDoesNotCrash`
- `LivePlayerTest.resumingAfterAPauseKeepsAdvancingFromWhereItStopped`

#### UC-14c Closing playback while the audio device is busy

Goal: close the player without a hang or a crash.

- If the audio device blocks a write when the player is closed, the close waits until the writer has stopped. An error thrown by the audio sink does not escape its thread.

Tests:
- `PcmFeederTest.closeDuringBlockedWriteReleasesOnlyAfterTheWriterStopped`
- `PcmFeederTest.anExceptionFromTheSinkDoesNotEscapeTheThread`

#### UC-14d The mini player on the shelf shows what is playing

Goal: control the book without opening it.

- The mini player shows the playing book, a play and pause button, and a close button ("Stop and close").

Tests: none yet

Unchecked: the mini player on a real device.

### UC-15 Change the playback speed

Goal: listen faster or slower.

- The speed button cycles through the speeds.
- The app remembers the speed.

Tests:
- `BookUiTest.playbackSpeedCyclesAndIsRemembered`

#### UC-15a The speeds are 1, 1.25, 1.5, 2 and 0.75

Goal: pick from a short list, in a known order.

- The button shows "<speed>×". One tap goes to the next speed in the order 1×, 1.25×, 1.5×, 2×, 0.75×, and then back to 1×.
- The slowest speed is 0.75× and the fastest is 2×. There is no speed outside this list.

Tests:
- `PlaybackLimitsTest.theSpeedsAreFiveAndTheSlowestIsThreeQuartersAndTheFastestTwice`
- `BookUiTest.playbackSpeedCyclesAndIsRemembered`

#### UC-15b The speed is kept

Goal: not set my speed again for each book.

- The speed is saved in the settings and used for every book after a restart.

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

#### UC-16a Skip back near the start stays at zero

Goal: not get a negative position.

- Skipping back within 10 seconds of the start goes to 0.

Tests:
- `SkipTest.stopsAtTheStartAndAtTheEndOfTheAudio`

#### UC-16b Skip forward near the end stays at the end

Goal: not skip past the end of the book.

- Skipping forward within 10 seconds of the end goes to the end of the audio.

Tests:
- `SkipTest.stopsAtTheStartAndAtTheEndOfTheAudio`

#### UC-16c Skip in a book with no audio stays at zero

Goal: not crash on an empty book.

- A book with no audio has a length of 0, and a skip leaves the position at 0.

Tests:
- `SkipTest.anEmptyBookStaysAtZero`

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

#### UC-17a Choose a number of minutes

Goal: sleep after a time I choose.

- The menu offers 5, 10, 15, 30, 45 and 60 minutes. The label shows "Sleep M:SS" and counts down.

Tests:
- `SleepTimerTest.countsDownWhilePlayingAndFiresOnceAtZero`

#### UC-17b Choose the end of the chapter

Goal: stop when the chapter I am in ends.

- "End of chapter" sets the timer to the end of the current chapter. The label shows "Sleep at chapter end", and the timer fires when playback reaches it.

Tests:
- `SleepTimerTest.chapterEndFiresWhenPlaybackReachesIt`

#### UC-17c The timer does not count while paused

Goal: not lose my sleep time when I pause.

- Time counts only while the book plays. Pausing holds the timer.

Tests:
- `SleepTimerTest.pausedTimeDoesNotCount`

#### UC-17d The timer fires once

Goal: not be stopped again after I press Play.

- When the timer reaches zero it pauses the book and is cleared. Playing again does not start it.

Tests:
- `SleepTimerTest.countsDownWhilePlayingAndFiresOnceAtZero`

#### UC-17e Off cancels, and a new choice replaces the old

Goal: change my mind.

- "Off" cancels the timer. A new minutes choice replaces the old one, and a chapter-end choice replaces a minutes timer (and the other way), so the old one never fires.

Tests:
- `SleepTimerTest.offCancelsAndANewTimerReplacesTheOld`
- `PlaybackLimitsTest.aChapterEndTimerReplacesAMinuteTimerSoTheCountdownNeverFires`
- `PlaybackLimitsTest.aMinuteTimerReplacesAChapterEndTimerSoReachingTheChapterDoesNotFire`

#### UC-17f The timer fires in the middle of a paragraph

Goal: be eased out, not cut off.

- The volume fades in 10 steps over about 1.5 seconds, and then the book pauses. The position is kept where it stopped, so Play carries on from there.

Tests:
- `SleepTimerUiTest.theTimerCountsInTheServiceAndPausesTheSpeechWhenItRunsOut`

Unchecked: that the fade is smooth to the ear.

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

#### UC-18a A paused book is not marked finished

Goal: not lose my place when I pause in the middle.

- A pause in the middle of the book saves the place and does not mark it finished.

Tests:
- `PositionTest.aPauseMidBookIsNotFinished`

#### UC-18b A place I moved to while paused is saved

Goal: keep a place I chose by tapping, without playing.

- Moving to a place while paused saves it. Just looking at a book that was never played saves nothing.

Tests:
- `PositionTest.savesAPlaceMovedToWhilePausedButNotTheFirstLookAtAnUnplayedBook`

#### UC-18c The app is killed while the book plays

Goal: find my place after the app was stopped by force.

- After the process is killed during playback, the reopened book shows the last saved paragraph.

Tests:
- `ForceStopTest.playUntilKilled`
- `ForceStopTest.theSamePlaceIsShownAfterTheForceStop`

### UC-19 Control playback outside the app

Goal: pause, skip and seek from the notification, the lock screen or a headset.

- A media notification shows the book and its buttons. Seek and headset keys drive the player.
- A mini player on the shelf shows the playing book, a play and pause button, and a close button.

Tests:
- `MediaNotificationTest.theNotificationShowsTheBookAndItsButtonsAndSeekAndHeadsetKeysDriveThePlayer`

Unchecked: the look of the notification on the lock screen, and a real Bluetooth headset.

#### UC-19a The first play asks for the notification permission

Goal: have the notification, and be asked only once.

- On Android 13 and later, the first time a book plays the app asks for the notification permission. It never asks again, whatever the answer.

Tests: none yet

Unchecked: the permission dialog on a real device.

#### UC-19b Playback works when the notification is refused

Goal: still listen when I said no.

- A refusal leaves playback working, only without the notification in the shade.

Tests: none yet

Unchecked: playback with the permission refused. The test device grants the permission.

### UC-20 Hear years and numbers read naturally

Goal: not hear "nineteen oh five" as digits.

- Years, decades and ranges are spoken in words. The transcript keeps the digits.

Tests:
- `SpokenYearsTest.aYearReachesTheVoiceInWordsWhileTheTranscriptKeepsTheDigits`
- `SpokenTextTest.nineteenHundredsAreReadAsTwoPairs`
- `SpokenTextTest.decadesAreReadInThePlural`
- `SpokenTextTest.rangesAreReadWithTo`
- `SpokenTextTest.amountsAndOtherNumbersAreLeftAlone`

#### UC-20a A year with no cue is still a year

Goal: hear a year in words even without "in" before it.

- A year before punctuation, or with no cue word, is read as a year. A year of two thousand-something is read as "two thousand and …" or as pairs.

Tests:
- `SpokenTextTest.aYearBeforePunctuationOrWithoutACueIsStillAYear`
- `SpokenTextTest.twoThousandsAreReadAsTwoThousandOrTwentyPairs`

#### UC-20b Text without digits is not touched

Goal: not change a sentence that needs no change.

Tests:
- `SpokenTextTest.textWithoutDigitsIsUntouched`

## Reading along

### UC-21 See the sentence being spoken

Goal: follow the text while I listen.

- The transcript highlights the speaking sentence and scrolls it into view.

Tests:
- `BookUiTest.playingMovesTheHighlightAlongTheTranscript`

#### UC-21a Chapters opening a book still being made

Goal: have marks make sense on text that has no audio yet.

- On sentences not yet spoken, chapter marks start at zero. On spoken sentences they start at their audio time.

Tests:
- `ChapterDetectionTest.marksOnUnspokenSentencesStartAtZeroAndSpokenOnesAtTheirAudioTime`

### UC-22 Tap the text to jump

Goal: move to a place by touching it.

- Tapping a sentence seeks to it and speaks from there.
- Tapping the speaking paragraph pauses it, and tapping again resumes it.
- The first tap after opening a book starts speaking.

Tests:
- `BookUiTest.tappingASentenceSeeksToIt`
- `BookUiTest.tappingTheSpeakingParagraphPausesAndResumesAndAnotherParagraphSeeks`
- `BookUiTest.theFirstTapOnAnotherSentenceAfterOpeningTheBookStartsSpeaking`

#### UC-22a Jump into text that is not spoken yet

Goal: hear a later part of a book still being made first, without losing the rest.

- Tapping a sentence that is read but not yet spoken, or a chapter in the Chapters menu, moves speech there. Playback starts at that sentence when its audio is ready.
- Speech goes on from there to the end of the book, then wraps to the earliest sentence with no audio. It stops when every sentence has audio.
- A sentence that has audio is never spoken again. A sentence being spoken is not picked twice.
- Sentences already in progress finish first, so the jump takes effect after those few.
- The audio is kept in the order it was spoken while the book is made. When the book is finished it is put in the order of the text, and the saved place moves with it.
- The jump is kept in the book's folder, so a stopped book that carries on resumes from it.

Tests:
- `SpeakOrderTest.withoutAJumpTheBookIsSpokenInOrder`
- `SpeakOrderTest.afterAJumpSpeechStartsThereAndWrapsToTheEarliestSentenceWithoutAudio`
- `SpeakOrderTest.aSentenceThatHasAudioIsNeverSpokenAgain`
- `SpeakOrderTest.jumpingIntoSpokenTextContinuesAtTheFirstSentenceAfterItWithoutAudio`
- `SpeakOrderTest.aSecondJumpMovesOnFromWhereItLandsAndTheFirstGapStaysForTheWrap`
- `SpeakOrderTest.everySentenceIsSpokenExactlyOnceWhateverTheJumps`
- `SpeakOrderTest.textStillBeingReadIsSpokenBeforeTheWrapAndTheWrapComesAfterTheLastSentence`
- `SpeakOrderTest.takeWaitsForTextAndEndsWhenTheTextIsFinishedAndAllTaken`
- `SpeakOrderTest.aJumpWakesAWaitingSpeaker`
- `SpeakOrderTest.theJumpIsKeptInTheBooksFolderAndReadBack`
- `BookTextTest.aBookSpokenInOneRunShowsSpokenSentencesThenPendingOnes`
- `BookTextTest.aJumpLeavesPendingSentencesBetweenSpokenOnesInTheOrderOfTheText`
- `BookTextTest.aListFollowingTheAudioInTimeIsFoundByTheTimeNotThePositionInTheText`
- `BookTextTest.anOldTranscriptWithoutPlacesIsTheStartOfTheText`
- `BookTextTest.textNotReadAgainYetCutsTheListAtTheFirstSentenceNothingKnows`
- `BookTextTest.speechAfterAJumpSpeaksFromTheJumpWrapsAndNeverRepeatsASentence`
- `BookTextTest.aBookSpokenInOrderNeedsNoReordering`
- `JumpToPositionUiTest.tappingAnUnspokenSentenceMovesSpeechAndPlaybackThereAndTheSkippedTextIsSpokenAfterTheEnd`

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

#### UC-25a A contents page with titles and page numbers

Goal: get the chapter names and where they start.

- Rows with dot leaders, rows where numbers, labels and titles are in separate blocks, and a chapter with a description and a page range (it takes the first page of the range) are read.

Tests:
- `ChapterEstimatorTest.contentsRowsWithLeadersGiveTitlesAndPredictedPages`
- `ChapterEstimatorTest.numbersLabelsAndTitlesInSeparateBlocksAreMatchedByRow`
- `ChapterEstimatorTest.aChapterWithADescriptionAndAPageRangeTakesTheRangesFirstPage`
- `ChapterFixtureDumpTest.originContentsWithDescriptionsAndPageRanges`

#### UC-25b A contents page that carries on over the next page

Goal: not lose the chapters of a long contents list.

Tests:
- `ChapterEstimatorTest.contentsContinuingOnTheNextPageIsRead`

#### UC-25c A contents page with no page numbers

Goal: still get the titles.

- Titles are listed without pages. The first chapter's title gives the offset.

Tests:
- `ChapterEstimatorTest.aContentsPageWithoutPageNumbersListsTitlesOnly`
- `ChapterEstimatorTest.withoutRunningNumbersTheFirstChapterTitleGivesTheOffset`

#### UC-25d Roman numeral pages and wrapped titles

Goal: have a preface page and a long title read right.

- A Roman page gives no PDF page. A title wrapped over two lines is joined. A title that ends in letters that look like Roman numerals keeps its last word.

Tests:
- `ChapterEstimatorTest.aRomanPageGivesNoPdfPageAndAWrappedTitleIsJoined`
- `ChapterEstimatorTest.aTitleEndingInRomanLookingLettersKeepsItsLastWord`

#### UC-25e A page number that OCR misread

Goal: still find the chapter when one number is wrong.

- A misread number that falls back loses its page only, not its title. An entry whose number OCR lost is found on its opening page. A page number beside a running head is read from the edge line.

Tests:
- `ChapterEstimatorTest.aMisreadPageNumberThatFallsBackLosesItsPageOnly`
- `ChapterEstimatorTest.anEntryWhoseNumberOcrLostIsFoundOnItsOpeningPage`
- `ChapterEstimatorTest.aPageNumberIsReadFromAnEdgeLineBesideARunningHead`

#### UC-25f A chapter is marked only where its page predicts

Goal: not mark a chapter at a place where the title is only quoted.

- An anchor is noted only on the page predicted for a chapter. A chapter whose anchor is not in the text is left out. A chapter with a garbled heading is marked at its page's first paragraph.

Tests:
- `ChapterEstimatorTest.anchorsAreNotedOnlyOnThePagePredictedForAChapter`
- `CleanupTest.anEstimatedChapterWhoseAnchorIsNotInTheTextIsLeftOut`
- `CleanupTest.anEstimatedChapterWithAGarbledHeadingIsMarkedAtItsPagesFirstParagraph`

#### UC-25g Saved contents are used when the transcript has none

Goal: keep my chapters when the text alone does not show them.

Tests:
- `CleanupTest.usesSavedContentsWhenTheTranscriptHasNone`

#### UC-25h Analytic contents are left to text detection

Goal: not invent chapters from a contents page that has none.

- A book whose contents are an analytic summary, with no chapter pages, is left to the text detection.

Tests:
- `ChapterFixtureTest.gibbonAnalyticContents`
- `ChapterFixtureDumpTest.anAnalyticContentsIsLeftToTheTextDetection`
- `ChapterFixtureDumpTest.varietiesContentsWithSeveralLecturesUnderOneHeading`

#### UC-25i A long subtitle after a running head is kept

Goal: not lose a chapter subtitle.

Tests:
- `CleanupTest.keepsALongChapterSubtitleAfterARunningHead`

### UC-26 Read a long title without losing the buttons

Goal: use the book screen's buttons whatever the title.

- A long title takes two lines and then an ellipsis, so the Back, Share and search buttons stay in view.
- The controls and the progress under them are centred.

Tests:
- `BookHeaderTest.longTitleKeepsButtonsInViewAndEndsInEllipsis`
- `ControlsCentredTest.theButtonsAndTheProgressUnderThemAreCentred`

#### UC-26a The play, speed and notes controls share one corner radius

Goal: see controls that look like one set.

Tests:
- `BookUiTest.playSpeedAndNotesShareTheControlRadius`
- `ThemeTest.controlsShareOneEightDpRadius`

### UC-53 Search the book for a word

Goal: find where the book says something.

- The magnifying glass in the reader's header opens a search field. The match count, previous and next, and a close icon sit beside it, and the header keeps its other buttons.
- The search reads the transcript text on the phone: spoken sentences and sentences read but not yet spoken. It ignores case and accents and takes the query as plain text, not a pattern. It does not read footnote annotations or notes.
- The count reads "1 of 2". The text scrolls to the current match, and every match is marked in teal, a colour that no pen colour and not the spoken sentence uses. The current match is stronger. See UC-53h for marking all matches or one.
- Searching never starts or seeks the audio. Tapping the marked text jumps there as in UC-22.

Tests:
- `SearchTest.ignoresCaseAndAccents`
- `SearchTest.findsMatchesAcrossParagraphsInReadingOrder`
- `SearchTest.aQueryIsLiteralTextNotAPattern`
- `SearchTest.aWholeBookIsScannedInAFewMilliseconds`
- `SearchTest.matchColoursStayReadableUnderTheThemeText`
- `BookUiTest.searchStepsThroughMatchesAndWrapsAround`

#### UC-53a Several matches in one paragraph

Goal: see every place a short word occurs, even inside one paragraph.

- Matches may overlap: "aa" occurs twice in "aaa".

Tests:
- `SearchTest.findsEveryMatchInOneParagraph`
- `SearchTest.overlappingMatchesAreAllFound`

#### UC-53b Step to the next and previous match

Goal: walk through the matches in reading order.

- Next, previous and the keyboard's Search key move the current match. After the last match, next goes to the first. Before the first, previous goes to the last.

Tests:
- `SearchTest.nextAndPreviousWrapAtTheEnds`
- `BookUiTest.searchStepsThroughMatchesAndWrapsAround`

#### UC-53h Mark all matches or only the current one

Goal: see every match at once, or only the one I am on.

- The check mark in the search bar ("Highlight all matches") is on when the search opens. On, every match in the book is marked in teal, the current one stronger. Off, only the current match is marked. The count always covers the whole book.
- Only rows on screen, and the few the list keeps beside them, are drawn, so marking stays quick on a whole book, even for a one-letter query.
- The toggle changes the search marks only. It does not save a highlight in the book.

Tests:
- `BookUiTest.searchStepsThroughMatchesAndWrapsAround`

#### UC-53c A query with no match

Goal: know that the word is not in the book.

- The counter says "No matches" and the field stays open. A query longer than the text matches nothing.

Tests:
- `SearchTest.noMatchGivesAnEmptyList`
- `SearchTest.aQueryLongerThanTheTextMatchesNothing`
- `SearchTest.theCounterReadsLikeThis`
- `BookUiTest.searchWithNoMatchEmptyQueryRotationAndBack`

#### UC-53d An empty query

Goal: clear the field without a stale result.

- An empty or blank query has no matches, no counter and no marks.

Tests:
- `SearchTest.anEmptyOrBlankQueryMatchesNothing`
- `BookUiTest.searchWithNoMatchEmptyQueryRotationAndBack`

#### UC-53e Close the search

Goal: get the plain transcript back.

- The close icon and the Back button close the search and clear the marks. Back closes the search first and does not leave the book. The scroll position returns to where it was only if the search never scrolled to a match.

Tests:
- `BookUiTest.searchStepsThroughMatchesAndWrapsAround`
- `BookUiTest.searchWithNoMatchEmptyQueryRotationAndBack`

Unchecked: that closing with no match found restores the scroll position.

#### UC-53f Search a book that is still being made

Goal: search the text that exists so far.

- The search covers the sentences read so far. The count updates when more sentences arrive. The search does not cover sentences not yet read.

Tests: none yet

#### UC-53g The query survives a rotation

Goal: turn the phone without losing the search.

- A rotation keeps the open book, the open search, the query and the current match.

Tests:
- `BookUiTest.searchWithNoMatchEmptyQueryRotationAndBack`

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

#### UC-28a A book with no notes says so

Goal: know the panel is empty, not broken.

- With no notes and no highlights, the panel shows "No notes yet".

Tests: none yet

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

#### UC-30a A colour on an already highlighted paragraph changes it

Goal: recolour a highlight without removing it first.

- Picking a colour on a highlighted paragraph replaces its colour. Only a swipe with no wheel choice toggles the highlight off.

Tests:
- `HighlightsTest.togglingHighlightsInTheDefaultYellowThenRemovesIt_andAColourChangesAnExistingOne`

### UC-31 Read highlighted text in light and dark themes

Goal: keep highlighted text readable.

- Every highlight colour keeps the transcript text readable in both themes. A highlight is one translucent band per line, like a marker.

Tests:
- `HighlightsTest.everyHighlightColourKeepsTheTranscriptTextReadableInBothThemes`
- `HighlightsTest.theHighlightIsTranslucent`
- `HighlightsTest.oneBandPerLineEachWithItsOwnExtent_aShortLastLineGetsAShortBand`
- `HighlightUiTest.rendersTheMarkerLookToAnImage`

Unchecked: how the highlight looks to a person on a real screen.

#### UC-31a Bands do not touch and leave the text alone

Goal: see a tidy marker.

- Bands of adjacent lines do not touch, an empty line gets no band, the inset never eats a line, and a band can bleed a little wider on each side. The solid palette also keeps the text readable.

Tests:
- `HighlightsTest.bandsOfAdjacentLinesDoNotTouch`
- `HighlightsTest.anEmptyLineGetsNoBand`
- `HighlightsTest.theInsetNeverEatsALine_andBleedWidensEachSide`
- `HighlightsTest.theOpaquePaletteAlsoKeepsTheTextReadable`

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

#### UC-32a Edit without a split

Goal: fix a word and nothing else.

- With no new line in the text, the paragraph keeps its span, its note and its highlight, and shows the new text. Saving with no change keeps everything as it was.

Tests:
- `ParagraphEditTest.editingWithoutASplitKeepsSpanNoteAndHighlightOnTheNewText`

#### UC-32b Split in two

Goal: cut a paragraph that OCR joined.

- A new line splits the paragraph in two. The audio span is divided by text length with no gap. The highlight is on both parts, the note is on the first only, and later notes, highlights and chapters move down by one.

Tests:
- `ParagraphMenuUiTest.editingAParagraphIntoTwoKeepsTheHighlightOnBothAndTheNoteOnTheFirst`
- `ParagraphEditTest.splittingKeepsTheHighlightOnBothPartsAndTheNoteOnTheFirstOnly`
- `ParagraphEditTest.splittingDividesTheOriginalSpanByTextLengthAndLeavesNoGap`
- `ParagraphEditTest.splittingMovesLaterNotesHighlightsAndChaptersDownByOne`

#### UC-32c Blank lines make no empty paragraph

Goal: not get empty paragraphs from stray blank lines.

- Blank lines and spaces make no paragraph. Each line is trimmed.

Tests:
- `ParagraphEditTest.blankLinesAndSpacesMakeNoEmptyParagraph`
- `ParagraphEditEdgeTest.editedTextIsTrimmedLineByLine`

#### UC-32d Editing a paragraph to nothing is refused

Goal: not leave a paragraph that has no text.

- Saving empty text is refused with "A paragraph cannot be edited to nothing; delete it instead." Nothing changes.

Tests:
- `ParagraphEditEdgeTest.editingAParagraphToNothingIsRefusedAndSaysToDeleteIt`

#### UC-32e A book with no chapters stays that way after an edit

Goal: not get chapters I did not have.

Tests:
- `ParagraphEditTest.aBookWithNoStoredChaptersStaysThatWay`
- `ParagraphEditEdgeTest.aBookWithNoDetectedChaptersStaysThatWayAfterADelete`

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

#### UC-33a Delete a plain paragraph

Goal: remove junk with one confirmation of intent.

- A paragraph with no highlight is deleted at once. Its note goes with it, and later notes and highlights move up by one.

Tests:
- `ParagraphMenuUiTest.deletingAParagraphRemovesItAndMovesLaterNotesUp`
- `ParagraphEditTest.deletingRemovesTheParagraphItsNoteAndHighlightAndShiftsLaterOnesUp`

#### UC-33b Delete a highlighted paragraph

Goal: not lose a highlight by accident.

- The app asks "Delete this paragraph?" and says "Its note and highlight go with it. This cannot be undone." Cancel keeps the paragraph, its note and its highlight.

Tests:
- `ParagraphMenuUiTest.deletingAHighlightedParagraphAsksFirstAndCancelKeepsIt`

#### UC-33c Delete the first paragraph

Goal: remove a title page, and still hear the book from 0:00.

- The next paragraph takes the span, so the audio still starts at zero.

Tests:
- `ParagraphEditEdgeTest.deletingTheFirstParagraphGivesItsSpanToTheNextSoTheAudioStillStartsAtZero`

#### UC-33d Delete the last paragraph

Goal: remove junk at the end.

- The previous paragraph takes the span.

Tests:
- `ParagraphEditEdgeTest.deletingTheLastParagraphGivesItsSpanToThePreviousOne`

#### UC-33e Delete the only paragraph

Goal: not crash when the book has one paragraph.

- The transcript is empty afterwards.

Tests:
- `ParagraphEditEdgeTest.deletingTheOnlyParagraphLeavesAnEmptyTranscript`

#### UC-33f Delete the paragraph that opens a chapter

Goal: not keep a chapter with no first paragraph.

- The chapter on it is dropped, and later chapters move up.

Tests:
- `ParagraphEditEdgeTest.deletingTheParagraphThatOpensAChapterDropsTheChapterAndMovesLaterOnesUp`

## Library

### UC-34 Delete a book

Goal: free space, without deleting by accident.

- Delete on the shelf asks first. Cancel keeps the book, and confirming removes the book with its PDF, audio, transcript and notes.

Tests:
- `DeleteConfirmTest.deleteAsksFirstCancelKeepsAndConfirmDeletes`

#### UC-34a Cancel keeps the book

Goal: back out of a delete.

- The dialog "Delete audiobook?" has Cancel and Delete. Cancel changes nothing.

Tests:
- `DeleteConfirmTest.deleteAsksFirstCancelKeepsAndConfirmDeletes`

#### UC-34b A failed book can be deleted

Goal: clear a book that did not work.

- A failed book has the same Delete button as any other.

Tests:
- `DeleteConfirmTest.deleteAsksFirstCancelKeepsAndConfirmDeletes`

### UC-35 Back up one book

Goal: keep a copy of a book and my notes outside the app.

- Share on the book screen, then "Zip backup", shares one zip through the share sheet. There is no separate Back up button.
- Import on the shelf restores that zip as a new book. The zip format is unchanged.
- The zip holds the PDF or EPUB, the audio, the transcript, the notes, the highlights and the details.

Tests:
- `BookUiTest.theShareMenuOffersEveryFormatAndTheZipBackupRestoresAsABook`
- `BackupExtractTest.extractsTheKnownEntriesAndIgnoresOthers`
- `BackupExtractTest.extractsTheHighlights`

Unchecked: sending the zip to a real target app such as a cloud drive.

#### UC-35a A zip of a book is named for the book

Goal: recognise the file in my cloud drive.

- The file name is the title with unsafe characters replaced, cut to 80 characters, and "audiobook" if nothing is left.

Tests:
- `BookUiTest.theShareMenuOffersEveryFormatAndTheZipBackupRestoresAsABook`

#### UC-35b Back up a book that is still being made

Goal: know what happens if I share a zip too early.

- The Share button waits until the book is ready, like the other formats, so no zip of an unfinished book is made. A failure shows the toast "Export failed: <message>".

Tests:
- `ExportTest.exportWaitsUntilTheBookIsReady`

### UC-36 Restore a book from a backup

Goal: get a book back, with my notes.

- Import on the shelf takes a book zip and adds it as a new book, with transcript, notes and details.
- A zip that is not a backup is refused with a message. A zip with path traversal writes nothing outside the book's folder.

Tests:
- `BackupImportTest.importsABackupAsANewBookWithTranscriptNotesAndDetails`
- `BackupImportTest.refusesAZipThatIsNotABackupAndSaysSo`
- `BackupExtractTest.rejectsAZipWithNeitherPdfNorEpub`
- `BackupExtractTest.rejectsAZipWithoutTheAudio`
- `BackupExtractTest.rejectsPathTraversalAndWritesNothingOutside`

#### UC-36a A restored book is ready, with a new id

Goal: use the book at once.

- The restored book is Ready, never Queued, and has a new id. It keeps its transcript, notes, highlights and details.

Tests:
- `BackupImportTest.importsABackupAsANewBookWithTranscriptNotesAndDetails`

#### UC-36b Restore a book whose title is already on the shelf

Goal: not lose either copy.

- The restore adds a second book with the same title. Nothing is replaced.

Tests: none yet

#### UC-36c Restore an old backup that has no details file

Goal: restore a backup made before details were saved.

- With no `meta.json`, the title is the name of the zip, and the book is still Ready. A details file with a blank title is refused with "Not an audiobook backup: no title."

Tests: none yet

#### UC-36d A zip that is not a backup is refused

Goal: be told when I picked the wrong zip.

- A zip with neither a PDF nor an EPUB, or with no audio, or with no transcript, is refused. The message names what is missing: "Not an audiobook backup: missing <names>." A zip with no entries lists everything.
- Files inside a folder of the zip do not count as the book's files.

Tests:
- `BackupImportTest.refusesAZipThatIsNotABackupAndSaysSo`
- `BackupExtractTest.rejectsAZipWithNeitherPdfNorEpub`
- `BackupExtractTest.rejectsAZipWithoutTheAudio`
- `BackupErrorTest.aMissingAudioIsNamed`
- `BackupErrorTest.aMissingTranscriptIsNamed`
- `BackupErrorTest.everythingMissingIsNamedTogether`
- `BackupErrorTest.aZipWithNoEntriesIsRefusedAsMissingEverything`
- `BackupErrorTest.aFileInsideAFolderOfTheZipDoesNotCountAsTheBooksFile`

#### UC-36e A zip with an unsafe file name is refused

Goal: be safe from a hostile zip.

- A name that starts with "/" or has a ".." part is refused with "Not a valid backup: unsafe file name "<name>"." and nothing is written outside the book's folder.

Tests:
- `BackupExtractTest.rejectsPathTraversalAndWritesNothingOutside`
- `BackupErrorTest.anUnsafeNameIsQuotedInTheMessage`

#### UC-36f A file that cannot be read is refused

Goal: be told when the zip could not be opened.

- A file that cannot be opened gives "Could not read the file." A book that cannot be saved gives "Could not save the book." The shelf shows the message of the failure, or "Could not import the backup."

Tests: none yet

Unchecked: a full disk during a restore.

### UC-37 Back up and restore the whole library

Goal: move all my books to a new phone.

- Export backup in the settings makes one zip of every book. Import on the shelf restores every book in it.

Tests:
- `BackupImportTest.exportsTheWholeLibraryFromTheSettingsAndImportsItBack`

Unchecked: a library large enough to matter, in size and time.

#### UC-37a A library zip is told from a book zip by its first entry

Goal: use one Import button for both.

- Import sends a file to the restore unless it is a PDF or an EPUB, by name or by type. An EPUB is a zip too, so it is never restored as a backup. The picker offers PDF, EPUB and zip files. Audio exports (M4B, M4A) are not imported, because they have no transcript.
- If the first entry of the zip ends in ".zip", the file is a library and each inner zip is restored as a book. Otherwise the file is one book.

Tests:
- `BackupImportTest.exportsTheWholeLibraryFromTheSettingsAndImportsItBack`
- `BackupImportTest.importsABackupAsANewBookWithTranscriptNotesAndDetails`
- `BackupKindTest.aZipIsABackupWhateverTypeTheProviderReports`
- `BackupKindTest.aPdfOrAnEpubIsABookToImport`

#### UC-37b A book still being made is left out of the library backup

Goal: not get a broken book in my backup.

- Only books that are Ready are put in the library zip.

Tests: none yet

#### UC-37c A library with no ready book gives an empty zip

Goal: know what happens with nothing to back up.

- The zip is empty, and restoring it fails with "Not an audiobook backup: missing …". This is a confusing message. See [Gaps](#gaps).

Tests: none yet

### UC-38 Export a book as one audiobook file

Goal: listen in another audiobook player.

- Share, then "Audiobook (one M4B)", shares one M4B with the title, the author and the chapters.
- Share waits until the book is ready.

Tests:
- `ExportTest.exportsOneM4bWithChaptersTitleAndAuthor`
- `ExportTest.exportWaitsUntilTheBookIsReady`
- `ExportMetadataTest.metadataStatesTheFormatVersionAndBookDetails`

Unchecked: playing the file in a real third-party player.

#### UC-38a A book with no chapters is one chapter named for the book

Goal: still have a chapter list in my player.

Tests:
- `ExportSpansTest.aBookWithNoChaptersIsOneChapterNamedForTheBook`

#### UC-38b Audio before the first heading is a chapter named "Start"

Goal: not lose the opening of the book from the chapter list.

- If the first heading is after 0:00, a chapter named "Start" is put first. If the first heading is at 0:00, no "Start" is added.

Tests:
- `ExportSpansTest.audioBeforeTheFirstHeadingIsNamedStart`
- `ExportSpansTest.aHeadingAtTheVeryStartNeedsNoStart`

#### UC-38c Two headings at the same time make one chapter

Goal: not get an empty chapter.

Tests:
- `ExportSpansTest.twoHeadingsAtTheSameTimeMakeOneChapter`

#### UC-38d No more than 255 chapters are marked

Goal: get a file that the format can hold.

- The file format cannot count more than 255 chapters, so the list is cut so that it fits.

Tests:
- `ExportSpansTest.noMoreThan255ChaptersAreMarkedBecauseTheFormatCannotCountMore`

#### UC-38e A book with no speech is one empty chapter

Goal: not crash on an empty book.

Tests:
- `ExportSpansTest.aBookWithNoSpeechIsOneEmptyChapter`

#### UC-38f Exporting a book that is not finished is refused

Goal: not share half a book.

- Export says "The book's audio is not finished yet" and shares nothing. Any failure of an export shows the toast "Export failed: <message>".

Tests:
- `ExportTest.exportWaitsUntilTheBookIsReady`

### UC-39 Export one audio file per chapter

Goal: put chapters on a player that has no chapter support.

- Share, then "One M4A per chapter", shares one file per chapter. The durations match the chapters.

Tests:
- `ExportTest.exportsOneM4aPerChapterWithMatchingDurations`

### UC-40 Export a book as an EPUB

Goal: read the transcript, with its chapters and edits, in an e-reader.

- Share, then "EPUB", shares an EPUB 3 file with the title, the author, the chapters and the paragraphs.
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

#### UC-41a Spell check starts off, the other three start on

Goal: get good text without setting anything.

- "Spell check" is off by default. "Skip front matter", "Merge split sentences" and "Skip tables, lists & notes" are on by default.

Tests:
- `BookUiTest.cogOpensSettingsAndSwitchShowsItsState`

#### UC-41b The settings screen also holds backup, voice and theme

Goal: find everything in one place.

- The settings hold "Export backup" (UC-36, UC-37), "Voice" (UC-45) and "Theme" (UC-46).

Tests:
- `BackupImportTest.exportsTheWholeLibraryFromTheSettingsAndImportsItBack`
- `ThemeModeTest.optionsPaneSwitchesBetweenLightDarkAndFollowSystem`

### UC-42 Skip the front matter

Goal: start the speech at the first chapter, not at the copyright page.

- With "Skip front matter" on, speech starts at the first heading that is followed by prose. A book with no front matter is left alone.

Tests:
- `CleanupTest.trimsToFirstHeadingFollowedByProse`
- `CleanupTest.skipsAContentsEntryWithNoProseAfterIt`
- `CleanupTest.fallsBackToTheImprintBlock`
- `CleanupTest.leavesTextWithNoFrontMatterAlone`

#### UC-42a Front matter with no heading is cut at the imprint block

Goal: skip the copyright page even without a heading.

- Without a heading followed by prose, the text starts after the imprint block (the copyright page).

Tests:
- `CleanupTest.fallsBackToTheImprintBlock`

#### UC-42b A contents entry is not taken for the first chapter

Goal: not start the speech at the contents list.

Tests:
- `CleanupTest.skipsAContentsEntryWithNoProseAfterIt`

### UC-43 Skip tables, lists and notes

Goal: not hear tables and lists read aloud.

- With "Skip tables, lists & notes" on, table cells, numeric lines and runs of short unpunctuated lines are dropped, and back matter is cut.

Tests:
- `CleanupTest.dropsTableCells`
- `CleanupTest.dropsNumericLinesButKeepsChapterHeadings`
- `CleanupTest.dropsRunsOfShortUnpunctuatedLines`
- `CleanupTest.cutsAtBackMatterInTheSecondHalfOnly`
- `TextStreamTest.backMatterIsNeverSpokenButTheTextBeforeItIsReleasedEarly`

#### UC-43a Back matter is cut in the second half of the book only

Goal: not lose a chapter called "Notes" in the middle.

Tests:
- `CleanupTest.cutsAtBackMatterInTheSecondHalfOnly`
- `TextStreamTest.backMatterIsNeverSpokenButTheTextBeforeItIsReleasedEarly`

#### UC-43b Numeric lines go but chapter headings stay

Goal: drop page numbers and tables, not "Chapter 12".

Tests:
- `CleanupTest.dropsNumericLinesButKeepsChapterHeadings`

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

#### UC-45a The phone's voice is the default

Goal: need no download to start.

- A new install uses the phone's own text-to-speech engine.

Tests:
- `BookUiTest.voiceDropDownDefaultsToThePhonesVoiceAndListsAllThree`
- `BookUiTest.importUsesThePhonesVoiceByDefault`

#### UC-45b Kokoro: a larger, more natural voice

Goal: choose the better voice.

- Kokoro is a download of about 103 MB and uses 2 speaking lanes.

Tests:
- `BookUiTest.pickingKokoroMakesAnImportUseTheKokoroSynthesizer`

#### UC-45c Piper: a smaller natural voice

Goal: choose the lighter voice.

- Piper is a download of about 23 MB and uses 3 speaking lanes.

Tests:
- `BookUiTest.pickingPiperMakesAnImportUseThePiperSynthesizer`

#### UC-45d The old "natural voice" switch means Kokoro

Goal: keep my choice after an update.

- A saved choice from the first version, "natural", is read as Kokoro.

Tests:
- `BookUiTest.theOldNaturalVoiceSwitchMeansKokoro`

#### UC-45e A voice download that fails falls back

Goal: not be left with a voice that is not there.

- The app returns to the previous voice and says why. A download path that would write outside the voice folder is refused with "Unsafe path in the voice download". A voice that cannot be stored says "Could not store the voice".
- Two downloads at once leave one working voice.

Tests:
- `BookUiTest.aFailedVoiceDownloadFallsBackToThePreviousVoiceAndSaysWhy`
- `RealNeuralVoiceTest.twoDownloadsAtOnceLeaveOneWorkingVoice`

#### UC-45f Speaking lanes work at the same time

Goal: have a book made faster.

- The speaking lanes of a voice run at the same time without waiting for each other. Sentences are still written in order (UC-07d). The work runs at background priority and hands the priority back.

Tests:
- `RealNeuralVoiceTest.lanesSpeakAtTheSameTimeWithoutWaitingForEachOther`
- `BackgroundPriorityTest.neuralVoiceWorkRunsAtBackgroundPriorityAndHandsThePriorityBack`

#### UC-45g Speech is written as a WAV that the import can read

Goal: have any engine work.

- Each engine writes a WAV file, and the import reads it. A WAV round-trips unchanged.

Tests:
- `WavTest.roundTrips`
- `RealSpeechTest.androidEngineWritesAWav`
- `RealNeuralVoiceTest.neuralVoiceWritesAWavThatTheImportCanRead`

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

#### UC-46a Light and dark themes differ and stay readable

Goal: be able to read in both.

- The light and dark colour schemes differ. Text, chips and the grey progress bars are readable in each.

Tests:
- `ThemeTest.lightAndDarkDiffer`
- `ThemeTest.schemeTextIsReadable`
- `ThemeTest.themeChipsDrawFromTheSchemeAndAreReadable`
- `ThemeTest.progressGreysAreReadable`

### UC-47 Keep footnotes out of the speech, and still see them

Goal: not hear a scanned book's footnotes in the middle of a paragraph, and still read them.

- With "Footnotes as notes, not spoken" on (the default), a footnote on a scanned page is kept as an annotation of the paragraph that cites it. A footnote is found by its place and small type at the foot of the page, or by its label (`[3]`, `*`, `†`, `‡`, a superscript or a number the text cites). A numbered list or a figure caption is not taken for one.
- The reader shows the annotation under its paragraph in small muted type. It is read-only, is not the reader's own paragraph note, and a tap on it does not start the audio.
- The citation stays in the paragraph as a small raised mark, such as `[3]`, just where the text cites the note, so it is clear what the note is about. The mark is shown only, never spoken, and search, chapters and word counts do not see it. A note with no label has no mark.
- The annotation is never spoken, and is not in the transcript, so the audio has no gap for it, and search, chapters and word counts do not see it. A footnote with no marker in the text goes with the last paragraph of its page; none is dropped.
- The footnotes are kept in `footnotes.json` with the book, so they survive a restart, and they travel in a backup. A backup made by an older build has none and restores without them.
- An EPUB's footnotes are kept the same way when the book marks them: a block with class `footnote`, `endnote` or `rearnote`, `epub:type` footnote, endnote or rearnote, or ARIA role `doc-footnote` or `doc-endnote` (Project Gutenberg marks them so). The note goes with the nearest paragraph before it that cites it, and its `[3]` marker is taken out of the spoken text.
- EPUB export leaves the annotations out, so the exported book reads back as the same paragraphs.
- The option applies to books made from now on: a book already made keeps its text. With the option off, a book reads exactly as it did before the option existed.

Tests:
- `FootnotesTest.takesAFootnoteOutOfThePageAndMatchesItToTheParagraphThatCitesIt`
- `FootnotesTest.aNumberedListIsNotAFootnote`
- `FootnotesTest.aFootnoteGoesWithItsParagraphWhenAnEarlierOneIsDropped`
- `FootnotesTest.aNoteInBodySizedTypeIsFoundByItsLabel`
- `FootnotesTest.aNumberedItemIsANoteWhenTheBodyCitesItsNumber`
- `FootnotesTest.aPageOfNothingButNotesIsLeftAsText`
- `FootnotesTest.aSymbolNoteNeedsItsSymbolInTheBody`
- `FootnotesTest.anotherFootnoteIsPlacedAfterTheFirst`
- `FootnotesTest.placesTheFootnoteUnderTheCitingParagraphAmongTheWholeTranscript`
- `FootnotesTest.aFigureCaptionIsNotAFootnote`
- `FootnotesTest.anUnmatchedFootnoteStaysWithTheLastParagraphOnThePage`
- `FootnotesTest.twoFootnotesOnOnePageAreEachMatched`
- `FootnotesTest.aPageWithoutFootnotesIsUnchanged`
- `FootnotesTest.withTheOptionOffAPageReadsAsItAlwaysDid`
- `FootnotesTest.geometryFindsASmallTypeNoteAtTheFootOfThePageWhateverItSays`
- `FootnotesTest.markersSitJustAfterTheTextBeforeTheCitationEvenWhenTheWhitespaceDiffers`
- `FootnotesTest.anUnlabelledNoteHasNoMarkerAndAPlainNumberIsShownInBrackets`
- `FootnoteUiTest.aFootnoteIsAnAnnotationUnderItsParagraphAndIsNeverSpoken`
- `FootnoteUiTest.withTheOptionOffTheFootnoteIsSpokenAsBefore`
- `FootnoteUiTest.theFootnoteOptionIsOnByDefaultAndSavedWithTheOthers`
- `FootnoteUiTest.anEpubFootnoteIsAnAnnotationUnderItsParagraphAndIsNeverSpoken`
- `FootnoteUiTest.anEpubWithTheOptionOffSpeaksItsFootnoteAsBefore`
- `EpubTest.aMarkedFootnoteIsTakenOutOfTheTextAndMatchedToTheParagraphThatCitesIt`
- `EpubTest.withTheOptionOffAMarkedFootnoteIsReadAsBefore`
- `EpubTest.anEpub3NoteIsFoundByItsTypeAndAnUnlabelledOneGoesWithTheParagraphBefore`
- `FootnoteUiTest.aBackupCarriesTheFootnotesAndRestoresThemWithTheBook`

### UC-52 See the stages of an import

Goal: see at a glance how far a book in the queue has come, without reading text.

- Each stage has an icon: a down arrow for fetching the file (done at once for a file picked on the device), a magnifier for reading, a speaker for speaking, a musical note for encoding. The play triangle is kept for playing the audio. An icon is grey when its stage is next but not started, blue while it runs, and white once done (the text colour on a light theme). Stages after the next one are not shown.
- Above the icons, each running stage has a thin bar and its percentage with its total, such as "42% · 1,200/3,000 words" or "25% · 3/12 pages". Reading and speaking can run together. A fetch of unknown length has a bar that runs without end and shows the size so far.
- An imported book has no "Ready" word and no icon. A bar shows how far the book has been played, with the percentage and the time listened over the length, such as "42% · 1:24/3:20 · Chapter 3", the last part being the chapter reached. A book never played reads "0% · 0:00/3:20" with no chapter.
- A waiting book says nothing in words: its place is its row. Only the error of a failed fetch shows, as "Download failed: …".
- Delete is a bin icon on the row.

Tests:
- `StagesTest.aQueuedPdfShowsItsFetchAsDoneAndReadingAsNext`
- `StagesTest.aBookFromFindBooksStartsWithItsFetchAndShowsNothingLater`
- `StagesTest.aFetchOfUnknownLengthHasNoFractionAndOnlyTheBytesSoFar`
- `StagesTest.readingShowsItsPercentAndPagesAndSpeakingIsNextUntilItHasWords`
- `StagesTest.aReadyBookShowsItsListeningAsTimeListenedOverItsLength`
- `StagesTest.readingAndSpeakingRunTogetherAndEncodingIsNext`
- `StagesTest.speakingShowsItsPercentAndWordsOutOfTheTotalWithReadingDone`
- `StagesTest.encodingShowsItsPercentWithEverythingBeforeItDone`
- `StagesTest.aFailedBookShowsNoStages`
- `FindBooksUiTest.aBookDownloadedOnItsOwnTurnShowsItsProgressOnItsResultAndItsRowAndThenCarriesOn`
- `ImportQueueUiTest.booksAreMadeOneAtATimeAndAQueuedBookCanBeDraggedUp`

## Maintainers

### UC-50 Refresh and measure the OCR fixtures

Goal: keep the OCR regression tests honest when the layout rules change.

- The dump tests write the OCR fixtures of the real scans and the survey of a book's pages. The benchmark test times each stage. They run by hand with extra arguments and are not part of the smoke set.

Tests:
- `OcrFixtureDumpTest.originOfSpecies`
- `OcrFixtureDumpTest.declineAndFall`
- `OcrFixtureDumpTest.varietiesOfReligiousExperience`
- `OcrFixtureDumpTest.varietiesPrefaceThenContents`
- `OcrFixtureDumpTest.radiantEnergy`
- `SurveyDumpTest.dump`

## Unsupported or known limits

These are things the app does not do, or does less well than a listener might expect. Each is stated from the code or from [docs/ocr-analysis.md](ocr-analysis.md).

- **A PDF with a text layer is still read with OCR.** The app does not use the text layer. This is slow (about 14 s per page on the emulator, not measured on a phone), and the analysis recommends a fast path.
- **Only Latin script is read.** The ML Kit recogniser does not read Cyrillic or Greek. A Russian page loses 100% of its words.
- **Tables, multiple columns and verse are not handled well.** Tables are dropped when "Skip tables, lists & notes" is on. Two-column pages and some verse can lose lines, or mix the columns. In the analysis, the layout rules drop a German verse and the last lines of one page.
- **Handwriting is not supported.** The app is made for printed pages.
- **A contents page, margin sidenotes and one long footnote can still be spoken.** The layout rules remove most running heads, page numbers, footnotes and index pages, not all.
- **An edited paragraph is not spoken again.** The audio keeps the original words (UC-32).
- **A book with DRM cannot be imported.** An EPUB with DRM is refused (UC-09).
- **A book is made only from the files on the phone or from Project Gutenberg.** Catalogue lists only English text books in the public domain, and only those with an EPUB.
- **Only the speeds 0.75×, 1×, 1.25×, 1.5× and 2× exist.**
- **Cloud services are not used for speech or OCR.** All of it runs on the phone. A voice that the phone does not have must be downloaded.
- **A failed book cannot be retried from the shelf.** There is no retry button; delete the book and import it again.

## Gaps

Entries with no test, and why:

- UC-03b: sharing an EPUB. The code path is the same as for a PDF, and a second emulator test would add time for no new code.
- UC-04b: an EPUB with no text. The check lives in the EPUB reader, and the existing tests build their files with text.
- UC-04e: an encrypted PDF and a PDF with zero pages. The result depends on the message of the Android renderer on a real device, and no such fixture is in the repository.
- UC-12b: the generation notification. It needs a real device to be seen.
- UC-14d: the mini player on the shelf. No test starts playback from the shelf and then looks at the mini player.
- UC-19a, UC-19b: the notification permission. The test device grants the permission, so no test can refuse it.
- UC-28a: the empty notes panel.
- UC-36b, UC-36c, UC-36f: restore of a same-title book, of a backup with no details file, and of a file that cannot be read. These call `Backup.restore`, which needs an Android context and a content resolver, so they are device tests.
- UC-37b, UC-37c: the library backup of a library with a book still being made, and with no ready book. They need two seeded books and a zip written on a device.
- UC-51b: the text-layer PDF that is read with OCR. It is a limit, not a feature.
- UC-48a: deleting a book in the middle of OCR.
- UC-53f: searching a book still being made. It needs a book that grows while the screen is open, and the count follows the same transcript reload as the text.
- UC-49d: see the bugs below.

Bugs found while writing these entries. They are not fixed here:

- `Library.readBook` throws on a damaged `meta.json`, and `Library.refresh` calls it for every book. One damaged details file would stop the whole shelf from loading (UC-49d).
- A library backup with no ready book is an empty zip. Restoring it says "Not an audiobook backup: missing …", which does not say that the library was empty (UC-37c).
- A share or "Open with" whose stream cannot be opened makes a failed book with the text "Could not read the PDF: null" (UC-04c).
- A full disk during the copy of the file shows the same "Could not read the PDF: …" message, which names the wrong cause (UC-04c).

Behaviour that tests cover only in part, and that someone has to check by hand on a phone:

- OCR quality and speed on the phone's own scans (UC-01, UC-05, UC-51a).
- A real process kill while a book is made (UC-12).
- The look of the notification, highlights, theme and layout (UC-19, UC-31, UC-46).
- Real voices, the real GitHub release, gutenberg.org and Open Library services, and the real Android spell checker (UC-06, UC-13, UC-44, UC-45).
- Exported files in third-party apps (UC-35, UC-38, UC-40).
