/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The saved options (SharedPreferences file "settings") are a compatibility contract: stored values from released versions must keep reading.
 * `schemas/options/v1.json` describes the stored keys; `golden/options/v1/` holds stored maps from each era with the settings they must give.
 * Both are frozen once released; a new key needs a new schema version.
 */
class OptionsSpecTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "schemas/options").isDirectory }
    private val schema = JSONObject(File(root, "schemas/options/v1.json").readText())
    private val properties = schema.getJSONObject("properties")
    private val goldenDir = File(root, "golden/options/v1")

    @After fun forgetLanguage() = CatalogueLanguage.load(FakePreferences())

    /** The settings every reader of the store sees, from the real read code, as JSON. */
    private fun effective(stored: Map<String, Any?>): JSONObject {
        val prefs = FakePreferences(stored)
        CatalogueLanguage.load(prefs)
        val o = Options.load(prefs)
        return JSONObject()
            .put("options", JSONObject().put("spell", o.spell).put("trimFront", o.trimFront).put("mergeFragments", o.mergeFragments)
                .put("cleanText", o.cleanText).put("footnotes", o.footnotes).put("contents", o.contents).put("voice", o.voice.name).put("theme", o.theme.name))
            .put("speed", Player.savedSpeed(prefs).toDouble())
            .put("askedNotifications", !MediaControl.markAsked(FakePreferences(stored)))
            .put("catalogueLanguage", CatalogueLanguage.chosen.value ?: JSONObject.NULL)
            .put("catalogueLanguages", JSONArray(Catalogue.savedLanguages(prefs).map { JSONObject().put("code", it.code).put("count", it.count) }))
    }

    private fun json(map: Map<String, Any?>) = JSONObject().also { j -> map.forEach { (k, v) -> j.put(k, if (v is Float) v.toDouble() else v) } }

    /** Writes every option away from its default through the real save code, and returns what the store then holds. */
    private fun writtenByTheApp(): Map<String, Any> {
        val prefs = FakePreferences()
        Options(spell = true, trimFront = false, mergeFragments = false, cleanText = false, footnotes = false, contents = false, voice = Voice.PIPER, theme = ThemeMode.DARK).save(prefs)
        CatalogueLanguage.choose(prefs, "fr")
        Player.saveSpeed(prefs, 1.5f)
        SummarySettings(enabled = true, audio = true, apiKey = "sk-test", keyChecked = true).save(prefs)
        MediaControl.markAsked(prefs)
        Catalogue.rememberLanguages(prefs, listOf(LanguageCount("en", 60000), LanguageCount("fr", 2000)))
        return prefs.snapshot()
    }

    @Test fun everyKeyTheAppWritesIsInTheSchema() {
        val written = writtenByTheApp()
        assertTrue("keys written but not in the schema: ${written.keys - properties.keys().asSequence().toSet()}", properties.keys().asSequence().toSet().containsAll(written.keys))
        // The legacy key is only read; every other schema key must have been written by the app.
        assertEquals(properties.keys().asSequence().toSet() - "natural", written.keys)
    }

    /** The guard against silent drift: a key read or written in the sources with no schema entry fails here. */
    @Test fun everyKeyInTheSourcesIsInTheSchema() {
        val sources = File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" && "SharedPreferences" in it.readText() }.toList()
        assertTrue("no source mentions SharedPreferences", sources.isNotEmpty())
        val access = Regex("""\.(?:get|put)(?:String|StringSet|Int|Long|Float|Boolean)\(\s*"([^"]+)"|\.remove\(\s*"([^"]+)"\)|\bconst val \w*KEY\w* = "([^"]+)"""")
        val keys = sources.flatMap { f -> access.findAll(f.readText()).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } to f.name } }
        assertTrue("found only ${keys.size} keys; the scan is broken", keys.size >= 13)
        val known = properties.keys().asSequence().toSet()
        val unknown = keys.filter { it.first !in known }
        assertTrue("keys in the sources with no entry in schemas/options/v1.json (add a schema version): $unknown", unknown.isEmpty())
    }

    /** The shared validator on a map, as the file the app would store; returns its complaints, empty when valid. */
    private fun problems(stored: Map<String, Any>) = SpecValidator.validate(SpecValidator.schemaFile("options", 1), json(stored).toString()).joinToString("\n")

    @Test fun whatTheAppWritesSatisfiesTheSchema() {
        assertEquals("", problems(writtenByTheApp()))
        assertEquals("", problems(emptyMap()))
        assertTrue("a wrong type must fail the validator", problems(mapOf("voice" to "GONE")).isNotEmpty())
    }

    @Test fun everyGoldenSatisfiesTheSchema() {
        for (file in goldenDir.listFiles { f -> !f.name.endsWith(".expected.json") }!!) {
            val j = JSONObject(file.readText())
            assertEquals(file.name, "", problems(j.keys().asSequence().associateWith { j.get(it) }))
        }
    }

    @Test fun everyGoldenGivesItsExpectedSettings() {
        val stored = goldenDir.listFiles { f -> !f.name.endsWith(".expected.json") }!!.sortedBy { it.name }
        assertTrue("goldens missing", stored.size >= 7)
        for (file in stored) {
            val expected = JSONObject(File(goldenDir, file.name.removeSuffix(".json") + ".expected.json").readText())
            val actual = effective(JSONObject(file.readText()).let { j -> j.keys().asSequence().associateWith { k -> j.get(k).let { v -> if (v is Double || v is java.math.BigDecimal) (v as Number).toFloat() else v } } })
            assertTrue("${file.name}: expected $expected but got $actual", expected.similar(actual))
        }
    }

    @Test fun absentKeysGiveTheDocumentedDefaults() {
        val actual = effective(emptyMap())
        val o = actual.getJSONObject("options")
        for ((key, field) in mapOf("spell" to "spell", "trim" to "trimFront", "merge" to "mergeFragments", "clean" to "cleanText", "footnotes" to "footnotes", "contents" to "contents", "voice" to "voice", "theme" to "theme")) {
            assertEquals("default of $key", properties.getJSONObject(key).get("default"), o.get(field))
        }
        assertEquals(properties.getJSONObject("speed").getDouble("default"), actual.getDouble("speed"), 0.0)
        assertEquals(properties.getJSONObject("asked-notifications").getBoolean("default"), actual.getBoolean("askedNotifications"))
        assertTrue("no language chosen reads as none", actual.isNull("catalogueLanguage"))
        assertEquals(0, actual.getJSONArray("catalogueLanguages").length())
        assertEquals(properties.getJSONObject("language").getString("default"), CatalogueLanguage.ALL)
    }

    @Test fun spellCheckIsOnForAFreshInstallAndAStoredChoiceIsKept() {
        assertTrue(Options.load(FakePreferences()).spell)
        assertFalse(Options.load(FakePreferences(mapOf("spell" to false))).spell)
        assertTrue(Options.load(FakePreferences(mapOf("spell" to true))).spell)
        val prefs = FakePreferences(mapOf("spell" to false))
        Options.load(prefs).save(prefs)
        assertEquals(false, prefs.snapshot()["spell"])
        assertTrue("a fresh load must not write", FakePreferences().also { Options.load(it) }.snapshot().isEmpty())
    }

    /** Values outside the schema's enums are not valid data, so they are not golden files; the reader still copes with them. */
    @Test fun valuesOutsideTheSchemaFallBack() {
        val unknown = effective(mapOf("voice" to "GONE", "theme" to "SEPIA")).getJSONObject("options")
        assertEquals("PIPER", unknown.getString("voice"))
        assertEquals("SYSTEM", unknown.getString("theme"))
        assertEquals("KOKORO", effective(mapOf("voice" to "GONE", "natural" to true)).getJSONObject("options").getString("voice"))
        val languages = effective(mapOf("catalogue-languages" to "en:60000,bad,fr:x,de:3:4,es:7")).getJSONArray("catalogueLanguages")
        assertTrue(languages.toString(), JSONArray("[{\"code\":\"en\",\"count\":60000},{\"code\":\"es\",\"count\":7}]").similar(languages))
    }

    @Test fun unknownKeysAreIgnoredAndNeverRemoved() {
        val stored = mapOf("voice" to "PIPER", "from-the-future" to "x", "count" to 3)
        assertEquals(effective(mapOf("voice" to "PIPER")).toString(), effective(stored).toString())
        val prefs = FakePreferences(stored)
        Options.load(prefs).save(prefs)
        assertEquals("x", prefs.snapshot()["from-the-future"])
        assertEquals(3, prefs.snapshot()["count"])
    }

    @Test fun savingOptionsNeverWritesTheLegacyOrCatalogueKeys() {
        val prefs = FakePreferences(mapOf("natural" to true, "language" to "fr"))
        Options().save(prefs)
        assertEquals(true, prefs.snapshot()["natural"])
        assertEquals("fr", prefs.snapshot()["language"])
        assertFalse(prefs.snapshot().containsKey("speed"))
    }

    @Test fun whatTheAppWritesIsReadBack() {
        val actual = effective(writtenByTheApp())
        assertEquals("PIPER", actual.getJSONObject("options").getString("voice"))
        assertEquals("fr", actual.getString("catalogueLanguage"))
        assertEquals(1.5, actual.getDouble("speed"), 0.0)
        assertTrue(actual.getBoolean("askedNotifications"))
        assertEquals(2, actual.getJSONArray("catalogueLanguages").length())
    }
}
