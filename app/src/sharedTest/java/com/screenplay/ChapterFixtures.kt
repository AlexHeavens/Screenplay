/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * A few pages of a real book, for the chapter pre-pass ([ChapterEstimator]): the saved OCR of each page with its boxes
 * (`ocr/<name>.pages.json`) and the pages themselves (`ocr/<name>.pdf`, in the same order, so the device test can check that
 * the saved OCR is still what OCR returns). The saved OCR keeps each page's number in the whole book, which is what the
 * estimate's page numbers refer to, so a fixture of five pages still predicts pages far outside them.
 *
 * [chapters] is the expected estimate: title and predicted PDF page, null where the contents give no page.
 */
class ChapterFixture(val name: String, val chapters: List<Pair<String, Int?>>, val offset: Int?, val contentsPages: Set<Int>)

object ChapterFixtures {
    /**
     * Gibbon, Decline and Fall, vol. 1 (1897), PDF pages 21-22 of the Wikimedia Commons scan "Decline and Fall of the Roman
     * Empire vol 1 (1897).djvu" (public domain). The contents are an analysis: each chapter is a heading, a two-line title
     * and some forty sub-entries with dates and page numbers over fifteen pages, so a chapter's own page is only the first
     * sub-entry's. The pre-pass cannot read that yet, and must then give no plan rather than a wrong one.
     */
    val gibbon = ChapterFixture("gibbon-contents", emptyList(), offset = null, contentsPages = emptySet())


    /** Origin of Species, first edition (1859), PDF pages 5-9, 17, 20 and 21 of the Wikimedia Commons scan "Origin of Species 1859 facsimile.djvu" (public domain). Contents laid out as a chapter heading, several rows of description and a page range ("7-43") at the right; the offset (10) comes from running heads such as "10 CHAP. I.". OCR errors in the titles ("INTRODƯCTION", "VL", "THEOBY") are kept as OCR returns them. */
    val origin = ChapterFixture(
        "origin-contents",
        listOf(
        "INTRODƯCTION" to 11,
        "CHAPTER I VARIATION UNDER DoMESTICATION" to 17,
        "CHAPTER II VARIATION UNDER NATURE" to 54,
        "CHAPTER III STRUGGLE FOR ExISTENCE" to 70,
        "CHAPTER IV NATURAIL SELECTION" to 90,
        "CHAPTER V LAWs OF VARIATION" to 141,
        "CHAPTER VL DIFFICULTIES ON THEOBY" to 181,
        "CHAPTER VII INSTINCT" to 217,
        "CHAPTER VIII HYBRIDISM" to 255,
        "CHAPTER IX ON THE IMPERFECTION OF THE GEOLOGICAL RECORD" to 289,
        "CHAPTER X ON THE GEOLOGICAL. SUCCESSION OF ORGANIC BEINGS" to 322,
        "CHAPTER XI GEOGRAPHỊCAL DISTRIBUTION" to 356,
        "CHAPTER XII GEOGRAPHICAL DISTRIBUTION-comtinued" to 393,
        "CHAPTER XIII MUTUAL ArFINITIES OF ORGANIC BEINGS: MORPHOLOGY" to 421,
        "CHAP TER XIV RECAPITULATION AND CONCLUSION" to 469,
        "INDEX" to 501
        ),
        offset = 10, contentsPages = setOf(5, 6, 7, 8, 9),
    )

    /** The Varieties of Religious Experience (William James, 1902), PDF pages 11-15, 17, 18 and 19 of the Wikimedia Commons scan "The varieties of religious experience, a study in human nature.djvu" (public domain). One heading can cover several lectures ("LECTURES IV AND V"), each with a long description whose lines end in page references. The last lecture was read as "XI" and its page lies past the pages the pre-pass reads, so it has none. */
    val varieties = ChapterFixture(
        "varieties-contents",
        listOf(
        "LECTURE I RELIGION AND NEUROLOGY" to 17,
        "LECTURE II CIRCUMSCRIPTION OF THE ToPIC" to 42,
        "LECTURE III THE REALITY OF THE UxSEEN" to 69,
        "LECTURES IV AND V THE RELIGION OF HEALTHY-MINDEDNESS" to 94,
        "LECTURES VI AND VII THE SICK SOUL" to 143,
        "LECTURE VIII THE DIVIDED SELE, AND THE PROCESS OF 11S UNIFICATION" to 182,
        "LECTURE IX CoNVERSION" to 205,
        "LECTURE X CONVERSION concluded" to 233,
        "LECTURES XI, XII, AND XIII SAINTLINESS" to 275,
        "LECTURES XIV AND XV THE VALUE OF SAINTLINESS" to 342,
        "LECTURES XVI AND XVII MrSTICISM" to 395,
        "LECTURE XVIII PHILOSOPHY" to 446,
        "LECTURE XIX OTHER CHARACTERISTICS" to 474,
        "LECTURE XI CoNCLUSIONS" to null
        ),
        offset = 16, contentsPages = setOf(11, 12, 13, 14, 15),
    )

    fun parsePages(json: String): List<OcrPage> = JSONArray(json).let { a ->
        List(a.length()) {
            val o = a.getJSONObject(it)
            OcrPage(o.getInt("page"), o.getInt("height"), o.getJSONArray("blocks").let { bs ->
                List(bs.length()) { b ->
                    bs.getJSONArray(b).let { ls ->
                        List(ls.length()) { l -> ls.getJSONArray(l).let { v -> OcrLine(v.getInt(0), v.getInt(1), v.getString(2), v.getInt(3)) } }
                    }
                }
            })
        }
    }

    fun dumpPages(pages: List<OcrPage>): String = JSONArray(pages.map { p ->
        JSONObject().put("page", p.page).put("height", p.height)
            .put("blocks", JSONArray(p.blocks.map { b -> JSONArray(b.map { l -> JSONArray(listOf(l.top, l.bottom, l.text, l.left)) }) }))
    }).toString(1)

    fun check(f: ChapterFixture, pages: List<OcrPage>) {
        val plan = ChapterEstimator.plan(pages)
        assertEquals("${f.name}: chapters", f.chapters, plan.chapters.map { it.title to it.page })
        assertEquals("${f.name}: offset", f.offset, plan.offset)
        assertEquals("${f.name}: contents pages", f.contentsPages, plan.contentsPages)
    }
}
