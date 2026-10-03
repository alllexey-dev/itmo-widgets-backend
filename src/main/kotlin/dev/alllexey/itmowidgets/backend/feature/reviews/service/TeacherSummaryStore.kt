package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditAction
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryStateRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Each call commits on its own; the run itself calls Gemini outside any transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class TeacherSummaryStore(
    private val summaries: TeacherSummaryRepository,
    private val states: TeacherSummaryStateRepository,
    private val audit: AdminAuditService,
    private val config: AiSummaryConfig,
    private val objectMapper: ObjectMapper,
) {
    fun claim(trigger: SummaryRunTrigger, now: Instant): Boolean = states.claim(now, now.minus(STALE_LEASE), trigger) == 1

    /** The audit row is written only by the admin who actually started the run. */
    fun claimManual(adminId: UUID, now: Instant): Boolean {
        if (!claim(SummaryRunTrigger.ADMIN, now)) return false
        audit.record(adminId, AdminAuditAction.AI_SUMMARIES_RUN_STARTED, AUDIT_TARGET, null)
        return true
    }

    fun release() {
        states.release()
    }

    fun state(): TeacherSummaryStateEntity = states.findById(TeacherSummaryStateEntity.ID).orElseThrow()

    /** Brings every row in line with [inputs], the eligible teachers; rows of other teachers lose input and content. */
    fun plan(inputs: Map<Int, TeacherSummaryInput>, now: Instant) {
        val rows = summaries.findInputRows().associateBy { it.teacherIsu }
        summaries.saveAll(inputs.values.filter { it.teacherIsu !in rows }.map { newRow(it, now) })
        for ((isu, row) in rows) {
            val input = inputs[isu]
            if (input != null && (row.inputHash != input.hash || row.inputCount != input.count)) {
                summaries.updateInput(isu, input.hash, input.count, now)
            }
        }
        val ineligible = rows.values.filter { it.teacherIsu !in inputs && (it.inputHash != null || it.contentHash != null) }
        ineligible.map { it.teacherIsu }.chunked(CHUNK).forEach { summaries.clearInput(it, now) }
    }

    /** The same for one teacher whose input changed during a run; null means no longer eligible. */
    fun planTeacher(isu: Int, input: TeacherSummaryInput?, now: Instant) {
        when {
            input == null -> summaries.clearInput(listOf(isu), now)
            summaries.existsById(isu) -> summaries.updateInput(isu, input.hash, input.count, now)
            else -> summaries.save(newRow(input, now))
        }
    }

    fun next(runStartedAt: Instant): TeacherSummaryEntity? =
        summaries.findNext(config.maxAttempts, runStartedAt, Limit.of(1)).firstOrNull()

    /** One request of the budget of the day of [now] in the budget zone, as Google resets the quota. */
    fun takeBudget(now: Instant): Boolean = states.takeBudget(LocalDate.ofInstant(now, config.budgetZone), config.dailyRequestBudget) == 1

    fun markAttempt(isu: Int, now: Instant) {
        summaries.markAttempt(isu, now)
    }

    /** False when the teacher stopped being eligible meanwhile; the content is then dropped. */
    fun recordSuccess(isu: Int, verdict: SummaryVerdict.Valid, input: TeacherSummaryInput, model: String, now: Instant): Boolean =
        summaries.recordContent(isu, objectMapper.writeValueAsString(verdict.summary), input.hash, input.count, verdict.level,
            verdict.confidence, model, now) == 1

    fun recordRejected(isu: Int, code: String, now: Instant) {
        summaries.recordRejected(isu, code.take(ERROR_LENGTH), now)
    }

    fun resetFailedAttempts(now: Instant) {
        summaries.resetFailedAttempts(now)
    }

    fun finish(outcome: SummaryRunOutcome, error: String?, generated: Int, failed: Int, requests: Int, now: Instant) {
        states.finish(outcome, error?.take(ERROR_LENGTH), generated, failed, requests, now)
    }

    private fun newRow(input: TeacherSummaryInput, now: Instant) =
        TeacherSummaryEntity(teacherIsu = input.teacherIsu, inputHash = input.hash, inputCount = input.count, updatedAt = now)

    companion object {
        /** Longer than any run: a whole day budget at the longest pause fits well within it. */
        val STALE_LEASE: Duration = Duration.ofHours(6)
        const val AUDIT_TARGET = "ai-summaries"
        private const val ERROR_LENGTH = 100
        private const val CHUNK = 1000
    }
}
