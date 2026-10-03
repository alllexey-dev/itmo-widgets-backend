package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAiSummaries
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryHiddenRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryStatus
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminTeacherSummary
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncStore
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherNamesService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryViews
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Admin-only view and control of the AI summaries. Teacher names come from the Reviews copies, otherwise from the
 * official directory, which is asked outside transactions; changes are audited in the transaction that makes them.
 */
@Service
class AdminAiSummariesService(
    private val access: AdminAccess,
    private val summaries: TeacherSummaryRepository,
    private val states: TeacherSummaryStateRepository,
    private val copies: ExternalTeacherReviewRepository,
    private val views: TeacherSummaryViews,
    private val summaryService: TeacherSummaryService,
    private val credentials: ServiceCredentialStore,
    private val users: AdminUserSummaries,
    private val teacherNames: TeacherNamesService,
    private val audit: AdminAuditService,
    private val config: AiSummaryConfig,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val transaction = TransactionTemplate(transactions)

    @Transactional(readOnly = true)
    fun state(adminId: UUID): AdminAiSummaries {
        access.requireAdmin(adminId)
        val state = states.findById(TeacherSummaryStateEntity.ID).orElseThrow()
        val counts = summaries.countByStatus().associate { it.label to it.total }
        val today = LocalDate.ofInstant(clock.instant(), config.budgetZone)
        return AdminAiSummaries(
            enabled = config.enabled,
            running = state.runningSince != null,
            runningSince = state.runningSince,
            model = config.model.takeIf(String::isNotBlank),
            keyStatus = credentials.state(ServiceCredential.GEMINI_API_KEY).status,
            lastStartedAt = state.lastStartedAt,
            lastFinishedAt = state.lastFinishedAt,
            lastTrigger = state.lastTrigger,
            lastOutcome = state.lastOutcome,
            lastError = state.lastError,
            lastGenerated = state.lastGenerated,
            lastFailed = state.lastFailed,
            lastRequests = state.lastRequests,
            ready = counts[AdminSummaryStatus.READY.name] ?: 0,
            pending = counts[AdminSummaryStatus.PENDING.name] ?: 0,
            failed = counts[AdminSummaryStatus.FAILED.name] ?: 0,
            hidden = counts[AdminSummaryStatus.HIDDEN.name] ?: 0,
            budgetDay = today,
            budgetUsed = if (state.budgetDay == today) state.budgetUsed else 0,
            dailyBudget = config.dailyRequestBudget,
        )
    }

    /** «Пересчитать всё»: starts a run in the background and returns the state with the lease already taken. */
    fun start(adminId: UUID): AdminAiSummaries {
        access.requireAdmin(adminId)
        summaryService.startManual(adminId)
        return state(adminId)
    }

    /** Most reviewed first; not transactional, so names missing from the copies are asked outside a transaction. */
    fun teachers(adminId: UUID, status: AdminSummaryStatus?, page: Int, size: Int): AdminPage<AdminTeacherSummary> {
        access.requireAdmin(adminId)
        val result = summaries.findAdminPage(status?.name, AdminPage.request(page, size))
        return AdminPage.of(result, views(result.content))
    }

    /** An unchanged request writes nothing. */
    fun setHidden(adminId: UUID, isu: Int, request: AdminSummaryHiddenRequest): AdminTeacherSummary {
        access.requireAdmin(adminId)
        transaction.executeWithoutResult {
            val row = find(isu)
            if ((row.hiddenAt != null) != request.hidden) {
                val now = clock.instant()
                summaries.setHidden(isu, now.takeIf { request.hidden }, adminId.takeIf { request.hidden }, now)
                val action = if (request.hidden) AdminAuditAction.AI_SUMMARY_HIDDEN else AdminAuditAction.AI_SUMMARY_SHOWN
                audit.record(adminId, action, target(isu), null)
            }
        }
        return view(isu)
    }

    /** Puts the teacher first in the queue with fresh attempts, then starts a run unless one is going. */
    fun regenerate(adminId: UUID, isu: Int): AdminTeacherSummary {
        access.requireAdmin(adminId)
        transaction.executeWithoutResult {
            val row = find(isu)
            if (row.inputHash == null) throw BusinessRuleException("Not enough reviews")
            if (row.hiddenAt != null) throw BusinessRuleException("Summary is hidden")
            if (!config.enabled) throw BusinessRuleException(TeacherSummaryService.DISABLED)
            summaries.request(isu, clock.instant())
            audit.record(adminId, AdminAuditAction.AI_SUMMARY_REGENERATION_REQUESTED, target(isu), null)
        }
        summaryService.requestTeacher(isu)
        return view(isu)
    }

    private fun find(isu: Int): TeacherSummaryEntity =
        summaries.findById(isu).orElse(null) ?: throw NotFoundException("Summary not found")

    private fun view(isu: Int): AdminTeacherSummary = views(listOf(find(isu))).single()

    private fun views(rows: List<TeacherSummaryEntity>): List<AdminTeacherSummary> {
        if (rows.isEmpty()) return emptyList()
        val copyNames = copies.findActiveTeacherNames(ReviewsSyncStore.PROVIDER.name, rows.map { it.teacherIsu })
            .associate { it.teacherIsu to it.teacherName }
        val hiders = users.of(rows.mapNotNull { it.hiddenBy })
        return rows.map { row ->
            AdminTeacherSummary(
                teacherIsu = row.teacherIsu,
                teacherName = copyNames[row.teacherIsu] ?: teacherNames.name(row.teacherIsu),
                status = status(row),
                inputCount = row.inputCount,
                reviewCount = row.contentCount,
                summary = views.content(row),
                hidden = row.hiddenAt != null,
                hiddenAt = row.hiddenAt,
                hiddenByName = row.hiddenBy?.let(hiders::get)?.name,
                attempts = row.attempts,
                lastAttemptAt = row.lastAttemptAt,
                lastError = row.lastError,
            )
        }
    }

    private fun status(row: TeacherSummaryEntity): AdminSummaryStatus = when {
        row.hiddenAt != null -> AdminSummaryStatus.HIDDEN
        row.contentHash != null && row.contentHash == row.inputHash -> AdminSummaryStatus.READY
        row.attempts > 0 -> AdminSummaryStatus.FAILED
        else -> AdminSummaryStatus.PENDING
    }

    private fun target(isu: Int) = "teacher:$isu"
}
