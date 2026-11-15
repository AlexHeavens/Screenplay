/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCaseTest {
    private fun check(want: String, title: String) = assertEquals(want, TitleCase.of(title))

    @Test fun capitalisesEachWordOfABlockCapitalTitle() = check("The Prince's Duties", "THE PRINCE'S DUTIES")
    @Test fun keepsRomanNumeralsUpperCase() { check("Chapter III", "CHAPTER III"); check("Part IV: The Fall", "PART IV: THE FALL"); check("Chapter I", "CHAPTER I") }
    @Test fun doesNotTakeWordsForRomanNumerals() = check("Civil Mild Did", "CIVIL MILD DID")
    @Test fun keepsSmallWordsLowerCaseExceptAtTheStart() = check("A Tale of the City and a Fog in the Night", "A TALE OF THE CITY AND A FOG IN THE NIGHT")
    @Test fun capitalisesASmallWordAfterAColon() { check("Part One: The Road", "PART ONE: THE ROAD"); check("Book: Of Kings", "BOOK: OF KINGS") }
    @Test fun keepsAcronymsUpperCase() { check("The USA and the UK", "THE USA AND THE UK"); check("Life in the U.S.S.R.", "LIFE IN THE U.S.S.R."); check("NASA and the FBI", "NASA AND THE FBI") }
    @Test fun lowersTheLetterAfterAnApostrophe() { check("Don't Go", "DON'T GO"); check("The Prince’s Duties", "THE PRINCE’S DUTIES"); check("I'll Go", "I'LL GO") }
    @Test fun capitalisesANameAfterAnApostropheInitial() { check("O'Brien and D'Artagnan", "O'BRIEN AND D'ARTAGNAN") }
    @Test fun capitalisesEachPartOfAHyphenatedWord() = check("My Mother-in-Law", "MY MOTHER-IN-LAW")
    @Test fun keepsTheLetterOfAnAppendix() { check("Appendix A", "APPENDIX A"); check("Section A of the Code", "SECTION A OF THE CODE") }
    @Test fun keepsOrdinalsLowerCase() = check("The 1st of May", "THE 1ST OF MAY")
    @Test fun keepsNumbersAndPunctuation() = check("Chapter 12. The End!", "CHAPTER 12. THE END!")
    @Test fun leavesMixedCaseTitlesAlone() { check("The Prince's Duties", "The Prince's Duties"); check("CHAPTER III The end", "CHAPTER III The end"); check("Chapter 1. Loomings.", "Chapter 1. Loomings.") }
    @Test fun leavesTitlesWithoutLettersAlone() { check("12", "12"); check("", "") }
}
