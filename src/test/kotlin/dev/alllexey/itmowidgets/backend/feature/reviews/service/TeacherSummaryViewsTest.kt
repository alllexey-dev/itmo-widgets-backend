package dev.alllexey.itmowidgets.backend.feature.reviews.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredScale
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryTag
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryScale
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant
import kotlin.test.*

@Import(TeacherSummaryViews::class, TeacherSummaryViewsTest.TestConfig::class)
class TeacherSummaryViewsTest @Autowired constructor(private val views: TeacherSummaryViews, private val em: TestEntityManager) :
    PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean fun objectMapper() = jacksonMapperBuilder().build()
    }

    @Test
    fun `a shown summary carries every field and the scales in order`() {
        row(
            A,
            content = content(
                scales = SummaryScaleKind.entries.map {
                    if (it == SummaryScaleKind.WORKLOAD) {
                        StoredScale(it, SummaryScaleValue.NOT_ENOUGH_DATA, null)
                    } else {
                        StoredScale(it, SummaryScaleValue.HIGH, "Причина ${it.jsonKey}")
                    }
                },
            ),
        )

        val summary = assertNotNull(views.shown(A))

        assertEquals(4, summary.reviewCount)
        assertEquals("Синтетическое описание сводки для теста.", summary.description)
        assertEquals(listOf("Понятные лекции"), summary.pros)
        assertEquals(listOf("Строгие дедлайны"), summary.cons)
        assertEquals(listOf(SummaryTag.AUTOMAT, SummaryTag.STRICT_DEADLINES), summary.tags)
        assertEquals(SummaryScaleKind.entries, summary.scales.map { it.kind })
        assertEquals(TeacherSummaryScale(SummaryScaleKind.WORKLOAD, SummaryScaleValue.NOT_ENOUGH_DATA, null), summary.scales.last())
        assertEquals("Причина explains", summary.scales.first().reason)
        assertEquals(SummaryLevel.MIXED, summary.level)
        assertEquals(SummaryConfidence.HIGH, summary.confidence)
        assertEquals(NOW, summary.generatedAt)
    }

    @Test
    fun `hidden, empty and ineligible rows show nothing`() {
        row(A, hidden = true)
        row(B, content = null)
        row(C, eligible = false)

        assertNull(views.shown(A))
        assertNull(views.shown(B))
        assertNull(views.shown(C))
        assertNull(views.shown(D))
    }

    @Test
    fun `unreadable content shows nothing and logs only the teacher`() {
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(TeacherSummaryViews::class.java) as Logger
        logger.addAppender(logs)
        try {
            row(A, content = "{not json")
            row(B, content = """{"format": 2, "description": "x", "pros": [], "cons": [], "tags": [], "scales": []}""")
            row(C, content = content(scales = SummaryScaleKind.entries.take(4).map { StoredScale(it, SummaryScaleValue.HIGH, "x") }))

            assertNull(views.shown(A))
            assertNull(views.shown(B))
            assertNull(views.shown(C))
            assertEquals(listOf(A, B, C).map { "AI summary content unreadable teacher=$it" }, logs.list.map { it.formattedMessage })
        } finally {
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `levels come only from shown confident summaries in the requested order`() {
        row(A, confidence = SummaryConfidence.HIGH, level = SummaryLevel.VERY_POSITIVE)
        row(B, confidence = SummaryConfidence.MEDIUM, level = SummaryLevel.NEGATIVE)
        row(C, confidence = SummaryConfidence.LOW)
        row(D, hidden = true)
        row(E, eligible = false)

        assertEquals(
            listOf(TeacherSummaryLevel(B, SummaryLevel.NEGATIVE), TeacherSummaryLevel(A, SummaryLevel.VERY_POSITIVE)),
            views.levels(listOf(E, B, D, C, A, 968999)),
        )
    }

    private fun row(
        isu: Int,
        content: String? = content(),
        hidden: Boolean = false,
        eligible: Boolean = true,
        confidence: SummaryConfidence = SummaryConfidence.HIGH,
        level: SummaryLevel = SummaryLevel.MIXED,
    ) = em.persistAndFlush(
        TeacherSummaryEntity(
            teacherIsu = isu, inputHash = if (eligible) "b".repeat(64) else null, inputCount = if (eligible) 5 else 0,
            content = content, contentHash = content?.let { "a".repeat(64) }, contentCount = content?.let { 4 },
            level = content?.let { level }, confidence = content?.let { confidence }, model = content?.let { "gemini-test-model" },
            generatedAt = content?.let { NOW }, hiddenAt = if (hidden) NOW else null, updatedAt = NOW,
        ),
    )

    private fun content(
        scales: List<StoredScale> = SummaryScaleKind.entries.map { StoredScale(it, SummaryScaleValue.MEDIUM, "Причина") },
    ): String = jacksonMapperBuilder().build().writeValueAsString(
        StoredSummary(
            description = "Синтетическое описание сводки для теста.",
            pros = listOf("Понятные лекции"),
            cons = listOf("Строгие дедлайны"),
            tags = listOf(SummaryTag.AUTOMAT, SummaryTag.STRICT_DEADLINES),
            scales = scales,
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-29T09:00:00Z")
        const val A = 968101
        const val B = 968102
        const val C = 968103
        const val D = 968104
        const val E = 968105
    }
}
