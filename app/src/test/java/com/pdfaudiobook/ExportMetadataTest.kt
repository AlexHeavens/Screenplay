/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportMetadataTest {
    @Test
    fun metadataStatesTheFormatVersionAndBookDetails() {
        val json = JSONObject(Export.metadata("Title", "Ada Author", listOf(Span("Start", 0, 5_000), Span("Chapter 1", 5_000, 9_000))).toString())
        assertEquals("0.0.1", json.getString("export_format"))
        assertEquals("Title", json.getString("title"))
        assertEquals("Ada Author", json.getString("author"))
        val chapters = json.getJSONArray("chapters")
        assertEquals(listOf("Start", "Chapter 1"), List(chapters.length()) { chapters.getJSONObject(it).getString("title") })
        assertEquals(listOf(0L, 5_000L), List(chapters.length()) { chapters.getJSONObject(it).getLong("start_ms") })
    }
}
