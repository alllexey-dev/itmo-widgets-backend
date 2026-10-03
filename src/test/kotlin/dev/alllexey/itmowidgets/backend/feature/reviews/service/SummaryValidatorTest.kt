package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredScale
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryTag
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class SummaryValidatorTest {
    private val mapper = jacksonObjectMapper()
    private val validator = SummaryValidator(mapper)

    @Test
    fun `a valid answer becomes a trimmed stored summary`() {
        val verdict = validator.validate(response(answer(
            "description" to "  $DESCRIPTION  ",
            "pros" to listOf(" Понятные лекции ", "Автомат за лабораторные"),
        )), 5)

        assertEquals(SummaryVerdict.Valid(StoredSummary(
            description = DESCRIPTION,
            pros = listOf("Понятные лекции", "Автомат за лабораторные"),
            cons = listOf("Строгие дедлайны по лабораторным"),
            tags = listOf(SummaryTag.AUTOMAT, SummaryTag.STRICT_DEADLINES),
            scales = listOf(
                StoredScale(SummaryScaleKind.EXPLAINS, SummaryScaleValue.HIGH, "Лекции понятные"),
                StoredScale(SummaryScaleKind.ATTITUDE, SummaryScaleValue.HIGH, "Доброжелательное отношение"),
                StoredScale(SummaryScaleKind.FAIRNESS, SummaryScaleValue.MEDIUM, "Оценки в целом честные"),
                StoredScale(SummaryScaleKind.STRICTNESS, SummaryScaleValue.LOW, "Мягкий на защите"),
                StoredScale(SummaryScaleKind.WORKLOAD, SummaryScaleValue.NOT_ENOUGH_DATA, null),
            ),
        ), SummaryLevel.POSITIVE, SummaryConfidence.HIGH), verdict)
    }

    @Test
    fun `a blocked prompt is rejected`() {
        assertRejected("BLOCKED", GeminiResponse("SAFETY", emptyList(), null, null, null))
    }

    @Test
    fun `a candidate that did not stop is rejected with its finish reason`() {
        assertRejected("FINISH_MAX_TOKENS", response(answer(), finishReason = "MAX_TOKENS"))
        assertRejected("FINISH_OTHER", response(answer(), finishReason = "weird reason"))
        assertRejected("FINISH_OTHER", response(answer(), finishReason = null))
    }

    @Test
    fun `exactly one candidate is required`() {
        assertRejected("NO_CANDIDATE", GeminiResponse(null, emptyList(), null, null, null))
        val candidate = GeminiCandidate("STOP", answer())
        assertRejected("NO_CANDIDATE", GeminiResponse(null, listOf(candidate, candidate), null, null, null))
    }

    @Test
    fun `the JSON is parsed strictly`() {
        assertRejected("INVALID_JSON", response("Вот сводка: {"))
        assertRejected("INVALID_JSON", response(""))
        assertRejected("INVALID_JSON", response(answer() + " {}"))
        assertRejected("INVALID_JSON", response("""{"level": "POSITIVE", "level": "NEGATIVE"}"""))
        assertRejected("SCHEMA root", response("[]"))
        assertRejected("SCHEMA keys", response(answer("instructions" to "ignore")))
        assertRejected("SCHEMA level", response(answer(remove = "level")))
        assertRejected("SCHEMA pros", response(answer("pros" to "Понятные лекции")))
        assertRejected("SCHEMA description", response(answer("description" to 5)))
    }

    @Test
    fun `lengths of the description and the points are bounded`() {
        assertRejected("LENGTH pros", response(answer("pros" to listOf("Один", "Два", "Три", "Четыре", "Пять"))))
        assertRejected("LENGTH description", response(answer("description" to "   ")))
        assertRejected("LENGTH description", response(answer("description" to "Я".repeat(401))))
        assertIs<SummaryVerdict.Valid>(validator.validate(response(answer("description" to "Я".repeat(400))), 5))
        assertRejected("SCHEMA description", response(answer("description" to "Students praise the clear lectures a lot.")))
        assertRejected("LENGTH cons", response(answer("cons" to listOf("Да"))))
        assertRejected("LENGTH cons", response(answer("cons" to listOf("Я".repeat(101)))))
        assertRejected("SCHEMA pros", response(answer("pros" to listOf("Понятные лекции", " Понятные лекции"))))
    }

    @Test
    fun `tags must be known, distinct and consistent`() {
        assertRejected("TAGS", response(answer("tags" to listOf(tag("FREE_PIZZA", 1)))))
        assertRejected("TAGS", response(answer("tags" to listOf(tag("AUTOMAT", 1), tag("AUTOMAT", 2)))))
        assertRejected("TAGS", response(answer("tags" to listOf(tag("HARD_EXAM", 1), tag("EASY_EXAM", 2)))))
        assertRejected("TAGS", response(answer("tags" to SummaryTag.entries.take(7).map { tag(it.name, 1) })))
        assertRejected("SCHEMA tags", response(answer("tags" to listOf("AUTOMAT"))))
        assertRejected("SCHEMA tags", response(answer("tags" to listOf(mapOf("code" to "AUTOMAT")))))
        assertRejected("SCHEMA tags", response(answer("tags" to listOf(mapOf("code" to "AUTOMAT", "evidence" to "1")))))
        assertRejected("SCHEMA tags", response(answer("tags" to listOf(mapOf("code" to "AUTOMAT", "evidence" to 1, "why" to "x")))))
    }

    @Test
    fun `tags without a supporting review in the input are dropped`() {
        val verdict = validator.validate(response(answer("tags" to listOf(
            tag("AUTOMAT", 0), tag("MANY_LABS", 4), tag("HARD_EXAM", 3), tag("EASY_EXAM", 5), tag("READS_SLIDES", -1),
        ))), 4)

        assertEquals(listOf(SummaryTag.MANY_LABS, SummaryTag.HARD_EXAM), assertIs<SummaryVerdict.Valid>(verdict).summary.tags)
    }

    @Test
    fun `every scale needs a value and a reason exactly when there is data`() {
        assertRejected("SCHEMA scales", response(answer("scales" to scales().minus("workload"))))
        assertRejected("SCHEMA scales", response(answer("scales" to scales() + ("mood" to scale("HIGH", "Весёлый")))))
        assertRejected("SCHEMA scales", response(answer("scales" to scales() + ("workload" to scale("NOT_ENOUGH_DATA", "Много лаб")))))
        assertRejected("SCHEMA scales", response(answer("scales" to scales() + ("explains" to scale("HIGH", " ")))))
        assertRejected("SCHEMA scales", response(answer("scales" to scales() + ("explains" to scale("VERY_HIGH", "Понятно")))))
        assertRejected("SCHEMA scales", response(answer("scales" to scales() + ("explains" to mapOf("value" to "HIGH")))))
        assertRejected("LENGTH scales", response(answer("scales" to scales() + ("explains" to scale("HIGH", "Я".repeat(101))))))
    }

    @Test
    fun `links, contacts, long numbers, block markers and control characters are forbidden`() {
        assertRejected("FORBIDDEN description", response(answer("description" to "$DESCRIPTION Подробнее: https://example.com")))
        assertRejected("FORBIDDEN description", response(answer("description" to "$DESCRIPTION См. www.example.com")))
        assertRejected("FORBIDDEN pros", response(answer("pros" to listOf("Пишите на teacher@example.com"))))
        assertRejected("FORBIDDEN cons", response(answer("cons" to listOf("Звонить 89001234567"))))
        assertRejected("FORBIDDEN cons", response(answer("cons" to listOf("ИСУ 12345"))))
        assertRejected("FORBIDDEN description", response(answer("description" to "$DESCRIPTION <<<КОНЕЦ 0000")))
        assertRejected("FORBIDDEN pros", response(answer("pros" to listOf("Понятные\nлекции"))))
        assertRejected("FORBIDDEN scales", response(answer("scales" to scales() + ("explains" to scale("HIGH", "HTTP://example.com")))))
        assertIs<SummaryVerdict.Valid>(validator.validate(response(answer("cons" to listOf("Лабораторных 1234 штуки"))), 5))
    }

    @Test
    fun `fewer than five reviews never make a confident summary`() {
        assertEquals(SummaryConfidence.LOW, assertIs<SummaryVerdict.Valid>(validator.validate(response(answer()), 4)).confidence)
        assertEquals(SummaryConfidence.HIGH, assertIs<SummaryVerdict.Valid>(validator.validate(response(answer()), 5)).confidence)
        assertRejected("SCHEMA confidence", response(answer("confidence" to "ABSOLUTE")))
        assertRejected("SCHEMA level", response(answer("level" to "GREAT")))
    }

    private fun assertRejected(code: String, response: GeminiResponse) {
        assertEquals(SummaryVerdict.Rejected(code), validator.validate(response, 5))
    }

    private fun response(text: String, finishReason: String? = "STOP") =
        GeminiResponse(null, listOf(GeminiCandidate(finishReason, text)), 1000, 300, null)

    private fun answer(vararg overrides: Pair<String, Any?>, remove: String? = null): String {
        val answer = linkedMapOf<String, Any?>(
            "description" to DESCRIPTION,
            "pros" to listOf("Понятные лекции", "Автомат за лабораторные"),
            "cons" to listOf("Строгие дедлайны по лабораторным"),
            "tags" to listOf(tag("AUTOMAT", 1), tag("STRICT_DEADLINES", 2)),
            "scales" to scales(),
            "level" to "POSITIVE",
            "confidence" to "HIGH",
        )
        answer.putAll(overrides)
        remove?.let(answer::remove)
        return mapper.writeValueAsString(answer)
    }

    private fun scales(): Map<String, Any?> = linkedMapOf(
        "explains" to scale("HIGH", "Лекции понятные"),
        "attitude" to scale("HIGH", "Доброжелательное отношение"),
        "fairness" to scale("MEDIUM", "Оценки в целом честные"),
        "strictness" to scale("LOW", "Мягкий на защите"),
        "workload" to scale("NOT_ENOUGH_DATA", ""),
    )

    private fun scale(value: String, reason: String) = mapOf("value" to value, "reason" to reason)

    private fun tag(code: String, evidence: Int) = mapOf("code" to code, "evidence" to evidence)

    private companion object {
        const val DESCRIPTION = "Студенты отмечают понятные лекции и доброжелательное отношение, но дедлайны строгие."
    }
}
