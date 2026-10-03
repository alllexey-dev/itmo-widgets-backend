package dev.alllexey.itmowidgets.backend.compat

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The fixture comparison of `src/test/resources/contract/README.md` on Gson trees: object key order
 * is ignored and two ISO date-times are equal when their instant and offset are; everything else is
 * exact, including integral against floating numbers.
 */
object JsonDifferences {
    fun of(expected: JsonElement, actual: JsonElement, path: String = "$"): List<String> = when {
        expected.isJsonObject && actual.isJsonObject -> {
            val expectedObject = expected.asJsonObject
            val actualObject = actual.asJsonObject
            val expectedKeys = expectedObject.keySet().toSortedSet()
            val actualKeys = actualObject.keySet().toSortedSet()
            (expectedKeys - actualKeys).map { "$path.$it: missing" } +
                (actualKeys - expectedKeys).map { "$path.$it: unexpected" } +
                expectedKeys.intersect(actualKeys).flatMap { of(expectedObject[it], actualObject[it], "$path.$it") }
        }

        expected.isJsonArray && actual.isJsonArray -> {
            val expectedArray = expected.asJsonArray
            val actualArray = actual.asJsonArray
            if (expectedArray.size() != actualArray.size()) {
                listOf("$path: ${expectedArray.size()} elements expected, ${actualArray.size()} found")
            } else {
                (0 until expectedArray.size()).flatMap { of(expectedArray[it], actualArray[it], "$path[$it]") }
            }
        }

        expected.isJsonPrimitive && actual.isJsonPrimitive &&
            samePrimitive(expected.asJsonPrimitive, actual.asJsonPrimitive) -> emptyList()

        expected.isJsonNull && actual.isJsonNull -> emptyList()

        else -> listOf("$path: $expected expected, $actual found")
    }

    private fun samePrimitive(expected: JsonPrimitive, actual: JsonPrimitive): Boolean = when {
        expected.isString && actual.isString ->
            expected.asString == actual.asString || sameDateTime(expected.asString, actual.asString)

        expected.isNumber && actual.isNumber ->
            integral(expected) == integral(actual) && expected.asBigDecimal.compareTo(actual.asBigDecimal) == 0

        expected.isBoolean && actual.isBoolean -> expected.asBoolean == actual.asBoolean

        else -> false
    }

    private fun integral(number: JsonPrimitive): Boolean = number.asString.none { it == '.' || it == 'e' || it == 'E' }

    private fun sameDateTime(expected: String, actual: String): Boolean {
        val expectedTime = dateTime(expected) ?: return false
        val actualTime = dateTime(actual) ?: return false
        return expectedTime.toInstant() == actualTime.toInstant() && expectedTime.offset == actualTime.offset
    }

    private fun dateTime(text: String): OffsetDateTime? = try {
        OffsetDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}
