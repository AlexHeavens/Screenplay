/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpecValidatorTest {
    private val schema = JSONObject("""{"type":"object","required":["id"],"properties":{"id":{"type":"integer","minimum":1},
        "kind":{"enum":["a","b"]},"tags":{"type":"array","items":{"type":"string"},"maxItems":2},"r":{"anyOf":[{"type":"null"},{"type":"integer"}]}}}""")

    private fun errors(json: String) = SpecValidator.validate(schema, org.json.JSONTokener(json).nextValue())

    @Test
    fun aValidDocumentHasNoErrors() = assertEquals(emptyList<String>(), errors("""{"id":3,"kind":"a","tags":["x"],"r":null}"""))

    @Test
    fun eachBrokenRuleIsReported() {
        for (bad in listOf("""{}""", """{"id":0}""", """{"id":1.5}""", """{"id":1,"kind":"z"}""", """{"id":1,"tags":["a","b","c"]}""", """{"id":1,"tags":[1]}""", """{"id":1,"r":"x"}""", "[]")) {
            assertTrue("accepted $bad", errors(bad).isNotEmpty())
        }
    }

    @Test
    fun aKeywordOutsideTheSubsetIsAnErrorNotSkipped() {
        try { SpecValidator.validate(JSONObject("""{"type":"string","format":"date"}"""), "x"); fail("expected a refusal") } catch (e: IllegalArgumentException) {}
    }

    @Test
    fun theCatalogueV2RowSchemaLoadsAndChecksARow() {
        val file = SpecValidator.schemaFile("catalogue", 2)
        val ok = """{"id":1,"title":"T","authors":"","languages":"en","downloads":0,"tags":[0],"rating10":null,"ratingCount":null}"""
        assertEquals(emptyList<String>(), SpecValidator.validate(file, ok))
        assertTrue(SpecValidator.validate(file, ok.replace("\"rating10\":null", "\"rating10\":99")).isNotEmpty())
    }
}
