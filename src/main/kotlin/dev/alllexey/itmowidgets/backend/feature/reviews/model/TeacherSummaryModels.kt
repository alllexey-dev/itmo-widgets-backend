package dev.alllexey.itmowidgets.backend.feature.reviews.model

/** The overall tone of a teacher's reviews. */
enum class SummaryLevel { VERY_NEGATIVE, NEGATIVE, MIXED, POSITIVE, VERY_POSITIVE }

/** How much the reviews agree; `LOW` whenever fewer than five reviews went in. */
enum class SummaryConfidence { LOW, MEDIUM, HIGH }

/** The five scales in display order; [jsonKey] names the scale in the model's answer. */
enum class SummaryScaleKind(val jsonKey: String) {
    EXPLAINS("explains"),
    ATTITUDE("attitude"),
    FAIRNESS("fairness"),
    STRICTNESS("strictness"),
    WORKLOAD("workload"),
}

enum class SummaryScaleValue { LOW, MEDIUM, HIGH, NOT_ENOUGH_DATA }

/** The fixed tag list of the system instruction; clients skip codes they do not know. */
enum class SummaryTag {
    AUTOMAT,
    MANY_LABS,
    HEAVY_HOMEWORK,
    FREQUENT_TESTS,
    STRICT_DEFENSE,
    SOFT_DEFENSE,
    HARD_EXAM,
    EASY_EXAM,
    ASKS_THEORY,
    STRICT_DEADLINES,
    FLEXIBLE_DEADLINES,
    ATTENDANCE_REQUIRED,
    ATTENDANCE_OPTIONAL,
    BONUS_POINTS,
    CLEAR_REQUIREMENTS,
    UNCLEAR_REQUIREMENTS,
    INTERESTING_CLASSES,
    READS_SLIDES,
    QUICK_REPLIES,
    HARD_TO_REACH,
    ;

    companion object {
        /** Tags that contradict each other; a summary never carries both. */
        val EXCLUSIVE_PAIRS: List<Pair<SummaryTag, SummaryTag>> = listOf(
            STRICT_DEFENSE to SOFT_DEFENSE,
            HARD_EXAM to EASY_EXAM,
            STRICT_DEADLINES to FLEXIBLE_DEADLINES,
            ATTENDANCE_REQUIRED to ATTENDANCE_OPTIONAL,
            CLEAR_REQUIREMENTS to UNCLEAR_REQUIREMENTS,
            QUICK_REPLIES to HARD_TO_REACH,
        )
    }
}

enum class SummaryRunOutcome { COMPLETED, BUDGET_EXHAUSTED, RATE_LIMITED, NO_KEY, AUTH_FAILED, FAILED }

enum class SummaryRunTrigger { SCHEDULE, ADMIN }

/** The validated summary as stored in `teacher_summaries.content`; [format] versions the JSON shape. */
data class StoredSummary(
    val format: Int = FORMAT,
    val description: String,
    val pros: List<String>,
    val cons: List<String>,
    val tags: List<SummaryTag>,
    /** All five scales in [SummaryScaleKind] order. */
    val scales: List<StoredScale>,
) {
    companion object {
        const val FORMAT = 1
    }
}

/** [reason] is null exactly when [value] is `NOT_ENOUGH_DATA`. */
data class StoredScale(val kind: SummaryScaleKind, val value: SummaryScaleValue, val reason: String?)
