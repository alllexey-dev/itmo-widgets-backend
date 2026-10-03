package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryScale
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException

/**
 * What users see of the AI summaries. A summary is shown when it has content, is not hidden and the teacher is
 * still eligible. Callers hold the transaction.
 */
@Service
class TeacherSummaryViews(
    private val summaries: TeacherSummaryRepository,
    private val objectMapper: ObjectMapper,
) {
    fun shown(isu: Int): TeacherSummary? {
        val row = summaries.findById(isu).orElse(null) ?: return null
        if (row.hiddenAt != null || row.inputHash == null) return null
        return content(row)
    }

    /** The stored content whether shown or not; null without content or when it is unreadable. */
    fun content(row: TeacherSummaryEntity): TeacherSummary? {
        val content = row.content ?: return null
        val stored = read(content)
        if (stored == null) {
            logger.warn("AI summary content unreadable teacher={}", row.teacherIsu)
            return null
        }
        return TeacherSummary(
            reviewCount = checkNotNull(row.contentCount),
            description = stored.description,
            pros = stored.pros,
            cons = stored.cons,
            tags = stored.tags,
            scales = stored.scales.map { TeacherSummaryScale(it.kind, it.value, it.reason) },
            level = checkNotNull(row.level),
            confidence = checkNotNull(row.confidence),
            generatedAt = checkNotNull(row.generatedAt),
        )
    }

    /** Levels in the order of [isus]; teachers without a shown confident summary are left out. */
    fun levels(isus: Collection<Int>): List<TeacherSummaryLevel> {
        val levels = summaries.findShownLevels(isus).associate { it.teacherIsu to it.level }
        return isus.mapNotNull { isu -> levels[isu]?.let { TeacherSummaryLevel(isu, it) } }
    }

    /** Null unless the content is a complete summary of the known format. */
    private fun read(content: String): StoredSummary? {
        val stored = try {
            objectMapper.readValue(content, StoredSummary::class.java)
        } catch (_: IOException) {
            return null
        }
        val complete = stored.format == StoredSummary.FORMAT && stored.scales.map { it.kind } == SummaryScaleKind.entries &&
            stored.scales.all { (it.reason == null) == (it.value == SummaryScaleValue.NOT_ENOUGH_DATA) }
        return stored.takeIf { complete }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(TeacherSummaryViews::class.java)
    }
}
