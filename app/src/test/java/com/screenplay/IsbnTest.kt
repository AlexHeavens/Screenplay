/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsbnTest {
    @Test fun findsAHyphenatedIsbn13AfterItsLabel() {
        assertEquals("9780306406157", Isbn.find("First published 1998.\nISBN 978-0-306-40615-7\nPrinted in Spain"))
    }

    @Test fun findsAnIsbn13WithoutHyphensOrWithSpaces() {
        assertEquals("9780306406157", Isbn.find("ISBN: 9780306406157"))
        assertEquals("9780306406157", Isbn.find("ISBN-13: 978 0 306 40615 7"))
    }

    @Test fun findsAnIsbn10WithACheckDigitX() {
        assertEquals("080442957X", Isbn.find("ISBN 0-8044-2957-X"))
        assertEquals("0306406152", Isbn.find("ISBN-10: 0306406152"))
    }

    @Test fun rejectsABadCheckDigit() {
        assertNull(Isbn.find("ISBN 978-0-306-40615-8"))
        assertNull(Isbn.find("ISBN 0-306-40615-3"))
    }

    @Test fun skipsABadIsbnAndTakesTheNextValidOne() {
        assertEquals("0306406152", Isbn.find("ISBN 978-0-306-40615-8, ISBN 0-306-40615-2"))
    }

    @Test fun ignoresNumbersAfterTheIsbnSuchAsAYear() {
        assertEquals("0306406152", Isbn.find("ISBN 0-306-40615-2 1998"))
    }

    @Test fun findsABare13DigitIsbnWhenOcrLostTheLabel() {
        assertEquals("9780306406157", Isbn.find("Spain 978-0-306-40615-7 first edition"))
    }

    @Test fun findsNothingInOrdinaryText() {
        assertNull(Isbn.find("The war began in 1914 and ended in 1918. Call 0306 406152 for a copy."))
        assertNull(Isbn.find("ISBN"))
        assertNull(Isbn.find(""))
    }

    @Test fun readsTitleAndAuthorsFromAnOpenLibraryReply() {
        val json = """{"ISBN:9780306406157":{"title":"Dune","subtitle":"Book One","authors":[{"name":"Frank Herbert"},{"name":"Brian Herbert"}]}}"""
        assertEquals(BookInfo("Dune: Book One", "Frank Herbert, Brian Herbert"), Isbn.parse("9780306406157", json))
    }

    @Test fun anEmptyReplyIsNoBook() {
        assertNull(Isbn.parse("9780306406157", "{}"))
        assertNull(Isbn.parse("9780306406157", """{"ISBN:9780306406157":{"title":" "}}"""))
    }
}
