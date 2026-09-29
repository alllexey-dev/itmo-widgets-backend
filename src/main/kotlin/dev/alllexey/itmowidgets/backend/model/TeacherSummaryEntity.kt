package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * One teacher's summary. The input columns describe the current eligible reviews; the content columns hold the
 * summary shown to users, which may lag behind the input until a new one is built. Existing rows change only
 * through column-scoped repository updates, so an admin action and a run never overwrite each other.
 */
@Entity
@Table(name = "teacher_summaries")
class TeacherSummaryEntity(
    @Id val teacherIsu: Int,
    @Column(length = 64) var inputHash: String? = null,
    @Column(nullable = false) var inputCount: Int = 0,
    /** A `StoredSummary` JSON. */
    @Column(columnDefinition = "text") var content: String? = null,
    /** The input hash the content was built from. */
    @Column(length = 64) var contentHash: String? = null,
    var contentCount: Int? = null,
    @Enumerated(EnumType.STRING) @Column(length = 16) var level: SummaryLevel? = null,
    @Enumerated(EnumType.STRING) @Column(length = 8) var confidence: SummaryConfidence? = null,
    @Column(length = 100) var model: String? = null,
    var generatedAt: Instant? = null,
    var hiddenAt: Instant? = null,
    var hiddenBy: UUID? = null,
    /** An admin asked to rebuild this summary; it goes first in the queue. */
    var requestedAt: Instant? = null,
    @Column(nullable = false) var attempts: Int = 0,
    var lastAttemptAt: Instant? = null,
    /** A rejection code such as `SCHEMA scales`, never model output. */
    @Column(length = 100) var lastError: String? = null,
    @Column(nullable = false) var updatedAt: Instant,
) {
    override fun toString(): String = "TeacherSummaryEntity($teacherIsu)"
}
