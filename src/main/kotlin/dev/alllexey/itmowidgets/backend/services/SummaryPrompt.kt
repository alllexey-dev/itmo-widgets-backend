package dev.alllexey.itmowidgets.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Builds the Gemini request of one summary. Reviews go in as data inside a block whose label is random per request,
 * so a review cannot close the block; the teacher's name and ISU never go in.
 */
@Component
class SummaryPrompt(objectMapper: ObjectMapper) {
    private val systemInstruction: String = resource(SYSTEM_RESOURCE)
    private val responseSchema: JsonNode = objectMapper.readTree(resource(SCHEMA_RESOURCE))
    private val random = SecureRandom()

    fun request(input: TeacherSummaryInput): GeminiRequest {
        val label = label()
        val text = buildString {
            append("Отзывов: ").append(input.count).append(".\n\n")
            append("<<<ОТЗЫВЫ ").append(label).append(">>>\n")
            input.reviews.forEachIndexed { index, review ->
                if (index > 0) append("\n\n")
                val header = listOfNotNull(
                    review.subject?.let { clean(it, SUBJECT_LIMIT) }?.takeIf(String::isNotEmpty)?.let { "Предмет: $it" },
                    review.date?.let { "Дата: $it" },
                )
                append('[').append(index + 1).append(']')
                if (header.isNotEmpty()) append(' ').append(header.joinToString(" · "))
                append('\n').append(clean(review.text, TEXT_LIMIT))
            }
            append("\n<<<КОНЕЦ ").append(label).append(">>>")
        }
        return GeminiRequest(systemInstruction, text, responseSchema)
    }

    /**
     * Keeps line breaks only, collapses long runs of them and turns block markers into quotes, then cuts to
     * [limit] code points. Control characters go first, so removing one cannot assemble a marker.
     */
    internal fun clean(text: String, limit: Int): String {
        val printable = text.replace("\r\n", "\n").filter { it == '\n' || !Character.isISOControl(it) }
        val cleaned = printable.replace(NEWLINES, "\n\n").replace("<<<", "«").replace(">>>", "»").trim()
        if (cleaned.codePointCount(0, cleaned.length) <= limit) return cleaned
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, limit))
    }

    private fun label(): String = HexFormat.of().formatHex(ByteArray(LABEL_BYTES).also(random::nextBytes))

    private fun resource(path: String): String =
        checkNotNull(SummaryPrompt::class.java.getResourceAsStream(path)) { "Missing resource $path" }
            .use { it.readBytes().decodeToString() }

    companion object {
        /** Part of every input hash: a new version rebuilds all summaries within the budget. */
        const val PROMPT_VERSION = 1
        const val SYSTEM_RESOURCE = "/ai/teacher-summary-system.txt"
        const val SCHEMA_RESOURCE = "/ai/teacher-summary-schema.json"
        private const val TEXT_LIMIT = 3000
        private const val SUBJECT_LIMIT = 200
        private const val LABEL_BYTES = 8
        private val NEWLINES = Regex("\n{3,}")
    }
}
