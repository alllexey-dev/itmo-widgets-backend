package dev.alllexey.itmowidgets.backend.contract

import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads, writes and compares contract fixtures. The comparison is semantic: object key order is
 * ignored and two ISO date-times are equal when their instant and offset are (Gson writes
 * `10:00+03:00` where Jackson writes `10:00:00+03:00`); everything else is exact, including the
 * difference between integral and floating numbers.
 */
object ContractJson {
    private val mapper = ObjectMapper()
    private val printer = DefaultPrettyPrinter()
        .withSeparators(
            Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator(""),
        )
        .withObjectIndenter(DefaultIndenter("  ", "\n"))
        .withArrayIndenter(DefaultIndenter("  ", "\n"))

    fun parse(text: String): JsonNode = mapper.readTree(text)

    fun parse(bytes: ByteArray): JsonNode = mapper.readTree(bytes)

    fun tree(value: Any?): JsonNode = mapper.valueToTree(value)

    /** Two-space indent, LF line ends and a final newline, so recorded files pass the repository's text rules. */
    fun render(node: JsonNode): String = mapper.writer().with(printer).writeValueAsString(node) + "\n"

    fun differences(expected: JsonNode, actual: JsonNode, path: String = "$"): List<String> = when {
        expected.isObject && actual.isObject -> {
            val expectedKeys = expected.propertyNames().toSortedSet()
            val actualKeys = actual.propertyNames().toSortedSet()
            (expectedKeys - actualKeys).map { "$path.$it: missing" } +
                (actualKeys - expectedKeys).map { "$path.$it: unexpected" } +
                expectedKeys.intersect(actualKeys).flatMap { differences(expected[it], actual[it], "$path.$it") }
        }

        expected.isArray && actual.isArray ->
            if (expected.size() != actual.size()) {
                listOf("$path: ${expected.size()} elements expected, ${actual.size()} found")
            } else {
                (0 until expected.size()).flatMap { differences(expected[it], actual[it], "$path[$it]") }
            }

        expected.isString && actual.isString ->
            if (sameText(expected.stringValue(), actual.stringValue())) {
                emptyList()
            } else {
                listOf("$path: ${expected.stringValue()} expected, ${actual.stringValue()} found")
            }

        expected.isNumber && actual.isNumber ->
            if (sameNumber(expected, actual)) emptyList() else listOf("$path: $expected expected, $actual found")

        expected == actual -> emptyList()

        else -> listOf("$path: $expected expected, $actual found")
    }

    private fun sameText(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val expectedTime = dateTime(expected) ?: return false
        val actualTime = dateTime(actual) ?: return false
        return expectedTime.toInstant() == actualTime.toInstant() && expectedTime.offset == actualTime.offset
    }

    private fun dateTime(text: String): OffsetDateTime? = try {
        OffsetDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun sameNumber(expected: JsonNode, actual: JsonNode): Boolean = when {
        expected.isIntegralNumber && actual.isIntegralNumber -> expected.bigIntegerValue() == actual.bigIntegerValue()

        expected.isFloatingPointNumber && actual.isFloatingPointNumber ->
            expected.decimalValue().compareTo(actual.decimalValue()) == 0

        else -> false
    }
}
