package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredScale
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryTag
import org.springframework.stereotype.Component
import java.io.IOException

sealed interface SummaryVerdict {
    data class Valid(val summary: StoredSummary, val level: SummaryLevel, val confidence: SummaryConfidence) : SummaryVerdict

    /** [code] is a fixed rejection code such as `SCHEMA scales`; it never quotes the model output. */
    data class Rejected(val code: String) : SummaryVerdict
}

/**
 * Checks the model answer against the schema and the content rules; the response schema of Gemini only helps.
 * Tags whose evidence does not point at an input review are dropped, not rejected: the model may tag only what a
 * review states directly, and clients receive the codes alone.
 */
@Component
class SummaryValidator(objectMapper: ObjectMapper) {
    private val strict: ObjectMapper = objectMapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun validate(response: GeminiResponse, inputCount: Int): SummaryVerdict = try {
        valid(response, inputCount)
    } catch (rejection: Rejection) {
        SummaryVerdict.Rejected(rejection.code)
    }

    private fun valid(response: GeminiResponse, inputCount: Int): SummaryVerdict.Valid {
        if (response.blockReason != null) reject("BLOCKED")
        val candidate = response.candidates.singleOrNull() ?: reject("NO_CANDIDATE")
        if (candidate.finishReason != STOP) reject("FINISH_" + (candidate.finishReason?.takeIf(FINISH_REASON::matches) ?: "OTHER"))
        val root = parse(candidate.text)
        if (!root.isObject) reject("SCHEMA root")
        if (!FIELDS.containsAll(root.fieldNames().asSequence().toList())) reject("SCHEMA keys")
        FIELDS.firstOrNull { !root.has(it) }?.let { reject("SCHEMA $it") }

        val description = text(root["description"], "description")
        if (description.codePointLength() !in DESCRIPTION_LENGTH) reject("LENGTH description")
        if (description.none { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }) reject("SCHEMA description")
        val confidence = enum<SummaryConfidence>(root["confidence"], "confidence")
        return SummaryVerdict.Valid(
            summary = StoredSummary(
                description = description,
                pros = points(root["pros"], "pros"),
                cons = points(root["cons"], "cons"),
                tags = tags(root["tags"], inputCount),
                scales = scales(root["scales"]),
            ),
            level = enum<SummaryLevel>(root["level"], "level"),
            confidence = if (inputCount < CONFIDENT_INPUT) SummaryConfidence.LOW else confidence,
        )
    }

    private fun parse(text: String): JsonNode = try {
        strict.readTree(text)?.takeUnless { it.isMissingNode }
    } catch (_: IOException) {
        null
    } ?: reject("INVALID_JSON")

    private fun points(node: JsonNode, field: String): List<String> {
        if (!node.isArray) reject("SCHEMA $field")
        if (node.size() > MAX_POINTS) reject("LENGTH $field")
        val points = node.map { text(it, field) }
        if (points.any { it.codePointLength() !in POINT_LENGTH }) reject("LENGTH $field")
        if (points.toSet().size != points.size) reject("SCHEMA $field")
        return points
    }

    private fun tags(node: JsonNode, inputCount: Int): List<SummaryTag> {
        if (!node.isArray) reject("SCHEMA tags")
        if (node.size() > MAX_TAGS) reject("TAGS")
        val tags = node.map { tag ->
            if (!tag.isObject || tag.size() != TAG_FIELDS.size || !TAG_FIELDS.all(tag::has)) reject("SCHEMA tags")
            val code = tag["code"].takeIf(JsonNode::isTextual)?.asText() ?: reject("SCHEMA tags")
            val evidence = tag["evidence"].takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt() ?: reject("SCHEMA tags")
            val known = SummaryTag.entries.firstOrNull { it.name == code } ?: reject("TAGS")
            known to evidence
        }
        if (tags.map { it.first }.toSet().size != tags.size) reject("TAGS")
        val supported = tags.filter { (_, evidence) -> evidence in 1..inputCount }.map { it.first }
        if (SummaryTag.EXCLUSIVE_PAIRS.any { (first, second) -> first in supported && second in supported }) reject("TAGS")
        return supported
    }

    private fun scales(node: JsonNode): List<StoredScale> {
        if (!node.isObject || node.size() != SummaryScaleKind.entries.size) reject("SCHEMA scales")
        return SummaryScaleKind.entries.map { kind ->
            val scale = node[kind.jsonKey]?.takeIf(JsonNode::isObject) ?: reject("SCHEMA scales")
            if (scale.size() != SCALE_FIELDS.size || !SCALE_FIELDS.all(scale::has)) reject("SCHEMA scales")
            val value = enum<SummaryScaleValue>(scale["value"], "scales")
            val reason = text(scale["reason"], "scales")
            if (reason.codePointLength() > MAX_REASON) reject("LENGTH scales")
            if (reason.isEmpty() != (value == SummaryScaleValue.NOT_ENOUGH_DATA)) reject("SCHEMA scales")
            StoredScale(kind, value, reason.takeIf(String::isNotEmpty))
        }
    }

    /** A trimmed string without links, contacts, ISU-like numbers, block markers or control characters. */
    private fun text(node: JsonNode, field: String): String {
        val text = node.takeIf(JsonNode::isTextual)?.asText()?.trim() ?: reject("SCHEMA $field")
        val lower = text.lowercase()
        if (text.any(Character::isISOControl) || FORBIDDEN.any { it in lower } || DIGITS.containsMatchIn(text)) reject("FORBIDDEN $field")
        return text
    }

    private inline fun <reified T : Enum<T>> enum(node: JsonNode, field: String): T {
        val name = node.takeIf(JsonNode::isTextual)?.asText() ?: reject("SCHEMA $field")
        return enumValues<T>().firstOrNull { it.name == name } ?: reject("SCHEMA $field")
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private fun reject(code: String): Nothing = throw Rejection(code)

    /** Control flow only: no stack trace, no model text. */
    private class Rejection(val code: String) : RuntimeException(code, null, false, false)

    private companion object {
        const val STOP = "STOP"
        const val MAX_POINTS = 4
        const val MAX_TAGS = 6
        const val MAX_REASON = 100

        /** Fewer reviews than this never make a confident summary. */
        const val CONFIDENT_INPUT = 5
        val DESCRIPTION_LENGTH = 20..400
        val POINT_LENGTH = 3..100
        val FIELDS = listOf("description", "pros", "cons", "tags", "scales", "level", "confidence")
        val TAG_FIELDS = listOf("code", "evidence")
        val SCALE_FIELDS = listOf("value", "reason")
        val FORBIDDEN = listOf("http://", "https://", "www.", "@", "<<<", ">>>")
        val DIGITS = Regex("[0-9]{5}")
        val FINISH_REASON = Regex("^[A-Z_]{1,40}$")
    }
}
