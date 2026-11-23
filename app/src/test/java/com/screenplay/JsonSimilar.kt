package com.screenplay

import org.json.JSONArray
import org.json.JSONObject

// AGP 9 compiles unit tests against Android's org.json stub, which lacks `similar`; the real library has it at run time.
// A member always wins over these, so they only fill the compile-time gap.
fun JSONObject.similar(other: Any?): Boolean =
    other is JSONObject && length() == other.length() && keys().asSequence().all { other.has(it) && jsonSame(get(it), other.get(it)) }

fun JSONArray.similar(other: Any?): Boolean =
    other is JSONArray && length() == other.length() && (0 until length()).all { jsonSame(get(it), other.get(it)) }

private fun jsonSame(a: Any?, b: Any?): Boolean = when (a) {
    is JSONObject -> a.similar(b)
    is JSONArray -> a.similar(b)
    is Number -> b is Number && java.math.BigDecimal(a.toString()).compareTo(java.math.BigDecimal(b.toString())) == 0
    else -> a == b
}
