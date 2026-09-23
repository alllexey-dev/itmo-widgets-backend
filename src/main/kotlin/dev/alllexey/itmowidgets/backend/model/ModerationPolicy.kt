package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException

/** Wire fields are camelCase; persisted names match the lowercase database key grammar. */
data class ModerationPolicy(
    val premoderation: Boolean = true,
    val reportThreshold: Int = 3,
    val voteThreshold: Int = -3,
    val dailySubmissionLimit: Int = 20,
    val dailyReportLimit: Int = 10,
) {
    fun validate() {
        if (reportThreshold < 1 || voteThreshold > -1 || dailySubmissionLimit < 1 || dailyReportLimit < 1) {
            throw InvalidRequestDataException("Invalid moderation policy limits")
        }
    }

    fun toKeys(targetType: ModerationTargetType): Map<String, String> = mapOf(
        "premoderation" to premoderation.toString(),
        "report_threshold" to reportThreshold.toString(),
        "vote_threshold" to voteThreshold.toString(),
        "daily_submission_limit" to dailySubmissionLimit.toString(),
        "daily_report_limit" to dailyReportLimit.toString(),
    ).mapKeys { (key, _) -> "${targetType.name}.$key" }

    companion object {
        fun fromKeys(targetType: ModerationTargetType, values: Map<String, String>): ModerationPolicy {
            val defaults = ModerationPolicy().toKeys(targetType)
            fun value(key: String): String = "${targetType.name}.$key".let { values[it] ?: defaults.getValue(it) }
            return ModerationPolicy(
                value("premoderation").toBooleanStrict(), value("report_threshold").toInt(),
                value("vote_threshold").toInt(), value("daily_submission_limit").toInt(),
                value("daily_report_limit").toInt(),
            ).also { it.validate() }
        }
    }
}
