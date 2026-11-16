/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Checks JSON text against a schema from schemas/<name>/vN.json, for the unit tests (docs/data-specs.md). It supports the same keyword subset as
 * scripts/schemalib.py and, like it, throws on any other keyword, so a schema keyword is never silently skipped. No dependency beyond org.json.
 */
object SpecValidator {
    private val annotations = setOf("\$schema", "\$id", "\$comment", "title", "description", "examples", "default", "deprecated")
    private val keywords = setOf("type", "enum", "const", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern", "required",
        "properties", "additionalProperties", "items", "minItems", "maxItems", "anyOf", "oneOf", "allOf", "\$ref", "\$defs")

    /** The schemas folder of the repository, found from the module directory the tests run in. */
    fun schemaFile(name: String, version: Int) = File("../schemas/$name/v$version.json")

    /** Every way [json] (one document) breaks the schema in [schemaFile]; empty when it is valid. */
    fun validate(schemaFile: File, json: String): List<String> = validate(JSONObject(schemaFile.readText()), JSONTokenerValue(json))

    fun validate(schema: JSONObject, value: Any?): List<String> = ArrayList<String>().also { check(value, schema, schema, "$", it) }

    /** Parses one JSON value of any type ({@code null} comes back as JSONObject.NULL). */
    @Suppress("FunctionName")
    private fun JSONTokenerValue(text: String): Any? = org.json.JSONTokener(text).nextValue()

    private fun resolve(schema: Any, root: JSONObject): Any {
        var s = schema
        var hops = 0
        while (s is JSONObject && s.has("\$ref")) {
            val ref = s.getString("\$ref")
            require(ref.startsWith("#/\$defs/") && hops++ < 20) { "unsupported or circular \$ref $ref" }
            s = root.getJSONObject("\$defs").get(ref.removePrefix("#/\$defs/"))
        }
        return s
    }

    private fun typeOk(v: Any?, t: String) = when (t) {
        "string" -> v is String
        "boolean" -> v is Boolean
        "null" -> v == null || v == JSONObject.NULL
        "integer" -> v is Int || v is Long || v is java.math.BigInteger || ((v is Double || v is Float) && (v as Number).toDouble() % 1.0 == 0.0)
        "number" -> v is Number
        "array" -> v is JSONArray
        "object" -> v is JSONObject
        else -> throw IllegalArgumentException("unknown type $t")
    }

    private fun check(value: Any?, schemaAny: Any, root: JSONObject, where: String, out: MutableList<String>) {
        val schema = resolve(schemaAny, root)
        if (schema == true) return
        if (schema == false) { out += "$where: no value is allowed here"; return }
        schema as JSONObject
        for (key in schema.keys()) require(key in keywords || key in annotations || key.startsWith("x-")) { "unsupported schema keyword '$key' at $where" }
        if (schema.has("type")) {
            val t = schema.get("type")
            val types = if (t is JSONArray) (0 until t.length()).map { t.getString(it) } else listOf(t as String)
            if (types.none { typeOk(value, it) }) { out += "$where: expected ${types.joinToString("/")}, got $value"; return }
        }
        if (schema.has("enum") && (0 until schema.getJSONArray("enum").length()).none { same(schema.getJSONArray("enum").get(it), value) }) out += "$where: $value is not in the enum"
        if (schema.has("const") && !same(schema.get("const"), value)) out += "$where: $value is not ${schema.get("const")}"
        if (value is Number) {
            val d = value.toDouble()
            if (schema.has("minimum") && d < schema.getDouble("minimum")) out += "$where: $value is below the minimum"
            if (schema.has("maximum") && d > schema.getDouble("maximum")) out += "$where: $value is above the maximum"
            if (schema.has("exclusiveMinimum") && d <= schema.getDouble("exclusiveMinimum")) out += "$where: $value is not above the exclusive minimum"
            if (schema.has("exclusiveMaximum") && d >= schema.getDouble("exclusiveMaximum")) out += "$where: $value is not below the exclusive maximum"
        }
        if (value is String) {
            if (schema.has("minLength") && value.length < schema.getInt("minLength")) out += "$where: too short"
            if (schema.has("maxLength") && value.length > schema.getInt("maxLength")) out += "$where: too long"
            if (schema.has("pattern") && !Regex(schema.getString("pattern")).containsMatchIn(value)) out += "$where: does not match ${schema.getString("pattern")}"
        }
        if (value is JSONArray) {
            if (schema.has("minItems") && value.length() < schema.getInt("minItems")) out += "$where: too few items"
            if (schema.has("maxItems") && value.length() > schema.getInt("maxItems")) out += "$where: too many items"
            if (schema.has("items")) for (i in 0 until value.length()) check(value.get(i), schema.get("items"), root, "$where[$i]", out)
        }
        if (value is JSONObject) {
            val required = schema.optJSONArray("required")
            if (required != null) for (i in 0 until required.length()) if (!value.has(required.getString(i))) out += "$where: missing required '${required.getString(i)}'"
            val props = schema.optJSONObject("properties")
            for (name in value.keys()) {
                if (props != null && props.has(name)) check(value.get(name), props.get(name), root, "$where.$name", out)
                else if (schema.has("additionalProperties")) check(value.get(name), schema.get("additionalProperties"), root, "$where.$name", out)
            }
        }
        for (key in listOf("allOf", "anyOf", "oneOf")) {
            val subs = schema.optJSONArray(key) ?: continue
            val results = (0 until subs.length()).map { i -> ArrayList<String>().also { check(value, subs.get(i), root, where, it) } }
            val passed = results.count { it.isEmpty() }
            when {
                key == "allOf" -> results.forEach { out += it }
                key == "anyOf" && passed == 0 -> out += "$where: matches none of ${results.size} alternatives (${results.joinToString("; ") { it.first() }})"
                key == "oneOf" && passed != 1 -> out += "$where: matches $passed of ${results.size} alternatives, not exactly one"
            }
        }
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is JSONObject && b is JSONObject -> a.similar(b)
        a is JSONArray && b is JSONArray -> a.similar(b)
        else -> a == b
    }
}
