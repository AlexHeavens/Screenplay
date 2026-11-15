/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

/** A site whose data the Catalogue shows. */
data class DataSource(val name: String, val url: String, val provides: String)

/** The sites that contribute data to the Catalogue. The links row at the bottom of the screen shows one link per entry, so a new source is one entry here. */
val DATA_SOURCES = listOf(
    DataSource("Project Gutenberg", "https://www.gutenberg.org", "books"),
    DataSource("Open Library", "https://openlibrary.org", "ratings"),
)
