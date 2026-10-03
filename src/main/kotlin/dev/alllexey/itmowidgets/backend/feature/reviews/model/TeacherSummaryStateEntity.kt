package dev.alllexey.itmowidgets.backend.feature.reviews.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/** The running lease, the last run summary and the day budget of Gemini requests; the migration creates the only row. */
@Entity
@Table(name = "teacher_summary_state")
class TeacherSummaryStateEntity(
    @Id val id: Short = ID,
    var runningSince: Instant? = null,
    var lastStartedAt: Instant? = null,
    var lastFinishedAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(length = 16) var lastTrigger: SummaryRunTrigger? = null,
    @Enumerated(EnumType.STRING) @Column(length = 24) var lastOutcome: SummaryRunOutcome? = null,
    @Column(length = 100) var lastError: String? = null,
    @Column(nullable = false) var lastGenerated: Int = 0,
    @Column(nullable = false) var lastFailed: Int = 0,
    @Column(nullable = false) var lastRequests: Int = 0,
    /** The Pacific day [budgetUsed] counts, as Google resets the daily quota. */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    var budgetDay: LocalDate? = null,
    @Column(nullable = false) var budgetUsed: Int = 0,
) {
    companion object {
        const val ID: Short = 1
    }
}
