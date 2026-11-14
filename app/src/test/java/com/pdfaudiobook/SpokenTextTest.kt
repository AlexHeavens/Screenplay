/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTextTest {
    private fun say(s: String) = SpokenText.of(s)

    @Test fun nineteenHundredsAreReadAsTwoPairs() {
        assertEquals("in nineteen sixty-nine", say("in 1969"))
        assertEquals("in nineteen oh five", say("in 1905"))
        assertEquals("in nineteen hundred", say("in 1900"))
        assertEquals("in eighteen forty-eight", say("in 1848"))
        assertEquals("in eleven hundred", say("in 1100"))
    }

    @Test fun twoThousandsAreReadAsTwoThousandOrTwentyPairs() {
        assertEquals("in two thousand", say("in 2000"))
        assertEquals("in two thousand seven", say("in 2007"))
        assertEquals("in twenty ten", say("in 2010"))
        assertEquals("in twenty twenty-four", say("in 2024"))
        assertEquals("in twenty twenty", say("in 2020"))
    }

    @Test fun aYearBeforePunctuationOrWithoutACueIsStillAYear() {
        assertEquals("In nineteen sixty-nine, he left.", say("In 1969, he left."))
        assertEquals("It ended in nineteen sixty-nine.", say("It ended in 1969."))
        assertEquals("nineteen sixty-nine was a year.", say("1969 was a year."))
        assertEquals("March nineteen sixty-nine", say("March 1969"))
        assertEquals("4 July seventeen seventy-six", say("4 July 1776"))
    }

    @Test fun decadesAreReadInThePlural() {
        assertEquals("the nineteen sixties", say("the 1960s"))
        assertEquals("the nineteen sixties", say("the 1960's"))
        assertEquals("the nineteen hundreds", say("the 1900s"))
        assertEquals("the two thousands", say("the 2000s"))
        assertEquals("the twenty tens", say("the 2010s"))
        assertEquals("nineteen sixty-nine's", say("1969's"))
    }

    @Test fun rangesAreReadWithTo() {
        assertEquals("nineteen sixty-nine to seventy-two", say("1969–72"))
        assertEquals("nineteen sixty-nine to nineteen seventy-two", say("1969-1972"))
        assertEquals("1969-05", say("1969-05"))
        assertEquals("1972-1969", say("1972-1969"))
    }

    @Test fun amountsAndOtherNumbersAreLeftAlone() {
        for (s in listOf(
            "1,969 people", "1969 people", "about 1969 men", "$1969", "£1969.50", "1969.5", "1969%", "1969 km", "page 1969", "p. 1969",
            "pp. 1969", "No. 1969", "Table 1969", "12,1969", "1969/70", "19691", "a1969", "1066", "2100", "1969s of them", "3 1969 dollars",
        )) assertEquals(s, say(s))
    }

    @Test fun textWithoutDigitsIsUntouched() {
        assertEquals("nothing to see here", say("nothing to see here"))
    }
}
