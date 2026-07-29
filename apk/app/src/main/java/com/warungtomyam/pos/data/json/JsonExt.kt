package com.warungtomyam.pos.data.json

import org.json.JSONObject

/**
 * Null-safe JSON accessors shared by every parser in the app.
 *
 * Root cause they fix: Android's [JSONObject.optString] with a fallback returns
 * the fallback ONLY when the key is absent. When the key is present but set to
 * JSON `null`, `opt()` yields the `JSONObject.NULL` sentinel, which stringifies
 * to the literal `"null"` — so `optString("paymentMethod", null)` returns the
 * 4-char String `"null"`, never Kotlin null. [isNull] is true for both absent
 * and JSON-null, so it is the correct guard.
 */

/** Returns the value for [name], or null when the key is absent OR present as JSON null. */
fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)

/** Required non-null string; throws [ParseException] naming [name] if it is missing or JSON null. */
fun JSONObject.reqString(name: String): String =
    if (isNull(name)) throw ParseException(name) else getString(name)

/** Required non-null double; throws [ParseException] naming [name] if it is missing or JSON null. */
fun JSONObject.reqDouble(name: String): Double =
    if (isNull(name)) throw ParseException(name) else getDouble(name)

/** Required non-null int; throws [ParseException] naming [name] if it is missing or JSON null. */
fun JSONObject.reqInt(name: String): Int =
    if (isNull(name)) throw ParseException(name) else getInt(name)

/**
 * Thrown when a required JSON field is missing or null. Carries the offending
 * [field] name so callers can log exactly which field broke rather than a
 * generic "parse error".
 */
class ParseException(val field: String) :
    Exception("Missing or null required JSON field: $field")
