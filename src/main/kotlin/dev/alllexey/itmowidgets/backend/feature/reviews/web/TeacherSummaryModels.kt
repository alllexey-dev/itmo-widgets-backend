package dev.alllexey.itmowidgets.backend.feature.reviews.web

import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryTag
import java.time.Instant

/**
 * The AI summary shown in a teacher's reviews. [reviewCount] is the number of reviews the shown summary was built
 * from, at least 3; it may lag behind the current reviews until a new summary is built. [tags] are codes of the
 * fixed list, which clients skip when unknown. [scales] holds all five scales in [SummaryScaleKind] order.
 */
data class TeacherSummary(
    val reviewCount: Int,
    val description: String,
    val pros: List<String>,
    val cons: List<String>,
    val tags: List<SummaryTag>,
    val scales: List<TeacherSummaryScale>,
    val level: SummaryLevel,
    val confidence: SummaryConfidence,
    val generatedAt: Instant,
)

/** [reason] is null exactly when [value] is `NOT_ENOUGH_DATA`. */
data class TeacherSummaryScale(val kind: SummaryScaleKind, val value: SummaryScaleValue, val reason: String?)

/** The tone of a shown summary whose confidence is `MEDIUM` or `HIGH`. */
data class TeacherSummaryLevel(val teacherIsu: Int, val level: SummaryLevel)
