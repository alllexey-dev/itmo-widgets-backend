package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.reviews.web.StrictBooleanDeserializer
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummary
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant
import java.time.LocalDate

/**
 * The Reviews sync state and stored review counts. `last*` describe the latest run; [upstreamTeachers] and
 * [upstreamReviews] are the totals of the latest applied snapshot; the `reviews*` and [teachersActive] counts are stored rows.
 */
data class AdminReviewsSync(
    val enabled: Boolean,
    val running: Boolean,
    val runningSince: Instant?,
    val lastCheckedAt: Instant?,
    val lastChangedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastOutcome: ReviewSyncOutcome?,
    /** A short technical line such as `HTTP 503 /teacher/100123`; null unless the latest run failed. */
    val lastError: String?,
    val lastAdded: Int,
    val lastUpdated: Int,
    val lastRemoved: Int,
    val upstreamTeachers: Int,
    val upstreamReviews: Int,
    val reviewsTotal: Long,
    val reviewsActive: Long,
    val reviewsRemoved: Long,
    val teachersActive: Long,
)

/** Own teacher reviews by the state of their ISU check. */
data class AdminReviewVerification(val pending: Long, val verified: Long, val unverified: Long)

/**
 * AI summaries for the admin: the run state, summary counts by status and the Gemini requests of the budget day.
 * [budgetDay] is today in the budget zone; [budgetUsed] is zero when the stored day is another one.
 */
data class AdminAiSummaries(
    val enabled: Boolean,
    val running: Boolean,
    val runningSince: Instant?,
    /** Null when no model is configured. */
    val model: String?,
    val keyStatus: ServiceCredentialStatus,
    val lastStartedAt: Instant?,
    val lastFinishedAt: Instant?,
    val lastTrigger: SummaryRunTrigger?,
    val lastOutcome: SummaryRunOutcome?,
    /** A short technical line such as `RATE_LIMITED 429`; null after a successful run. */
    val lastError: String?,
    val lastGenerated: Int,
    val lastFailed: Int,
    val lastRequests: Int,
    val ready: Long,
    val pending: Long,
    val failed: Long,
    val hidden: Long,
    val budgetDay: LocalDate,
    val budgetUsed: Int,
    val dailyBudget: Int,
)

/** `READY`: the summary matches the reviews; `PENDING`: waits for a request; `FAILED`: an answer was rejected. */
enum class AdminSummaryStatus { READY, PENDING, FAILED, HIDDEN }

/**
 * One teacher's summary for the admin. [summary] is the stored content, given even when hidden so the admin sees
 * what is hidden; [reviewCount] is the count it was built from and [inputCount] the current one.
 */
data class AdminTeacherSummary(
    val teacherIsu: Int,
    val teacherName: String?,
    val status: AdminSummaryStatus,
    val inputCount: Int,
    val reviewCount: Int?,
    val summary: TeacherSummary?,
    val hidden: Boolean,
    val hiddenAt: Instant?,
    val hiddenByName: String?,
    val attempts: Int,
    val lastAttemptAt: Instant?,
    /** A rejection code such as `SCHEMA scales`. */
    val lastError: String?,
)

data class AdminSummaryHiddenRequest(
    @JsonDeserialize(using = StrictBooleanDeserializer::class)
    val hidden: Boolean,
)
