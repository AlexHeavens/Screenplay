/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSourcesTest {
    @Test
    fun theCatalogueCreditsProjectGutenbergForBooksAndOpenLibraryForRatings() {
        assertEquals(
            listOf(Triple("Project Gutenberg", "https://www.gutenberg.org", "books"), Triple("Open Library", "https://openlibrary.org", "ratings")),
            DATA_SOURCES.map { Triple(it.name, it.url, it.provides) },
        )
    }

    @Test
    fun everySourceHasAUniqueNameAndASecureHomeAddress() {
        assertEquals(DATA_SOURCES.size, DATA_SOURCES.map { it.name }.toSet().size)
        assertTrue(DATA_SOURCES.all { it.url.startsWith("https://") && it.name.isNotBlank() && it.provides.isNotBlank() })
    }
}
