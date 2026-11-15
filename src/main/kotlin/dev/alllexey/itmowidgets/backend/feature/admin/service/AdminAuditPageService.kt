package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.model.AdminAuditEntity
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAuditEntry
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherDisplayNames
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

/** The filters of the audit page; each is optional and they combine by AND. [from] and [to] are Moscow days, inclusive. */
data class AdminAuditFilter(
    val actorIsu: Int? = null,
    val action: AdminAuditAction? = null,
    val targetType: String? = null,
    val target: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/**
 * The admin-only audit page, newest first. A page takes a fixed number of queries in one read-only transaction: the
 * entries, their actors and the users they name. Teachers are named after it, by [TeacherDisplayNames].
 */
@Service
class AdminAuditPageService(
    private val access: AdminAccess,
    private val audit: AdminAuditRepository,
    private val summaries: AdminUserSummaries,
    private val users: UserRepository,
    private val teacherNames: TeacherDisplayNames,
    transactions: PlatformTransactionManager,
) {
    private val readOnly = TransactionTemplate(transactions).apply { isReadOnly = true }

    fun page(adminId: UUID, filter: AdminAuditFilter, page: Int, size: Int): AdminPage<AdminAuditEntry> {
        val result = readOnly.execute { entries(adminId, filter, page, size) }!!
        val teachers = teacherNames.of(result.items.mapNotNull { isuIn(it.target, TEACHER) })
        return result.copy(
            items = result.items.map { entry -> isuIn(entry.target, TEACHER)?.let { entry.copy(targetName = teachers[it]) } ?: entry },
        )
    }

    private fun entries(adminId: UUID, filter: AdminAuditFilter, page: Int, size: Int): AdminPage<AdminAuditEntry> {
        access.requireAdmin(adminId)
        if (filter.from != null && filter.to != null && filter.from > filter.to) throw InvalidRequestDataException("from is after to")
        val result = audit.findPage(
            AdminPage.request(page, size),
            filter.actorIsu,
            filter.action?.name,
            filter.targetType,
            filter.target,
            filter.from?.let(::startOf),
            filter.to?.let { startOf(it.plusDays(1)) },
        )
        val actors = summaries.of(result.content.map { it.actorId })
        val userNames = userNames(result.content)
        return AdminPage.of(
            result,
            result.content.map { entry ->
                val actor = actors.getValue(entry.actorId)
                AdminAuditEntry(
                    entry.id,
                    entry.action,
                    entry.target,
                    entry.details,
                    entry.createdAt,
                    actor.isu,
                    actor.name,
                    targetName = isuIn(entry.target, USER)?.let(userNames::get),
                )
            },
        )
    }

    /** Stored names of the users the page names, in one query; a user without a name or account is absent. */
    private fun userNames(entries: List<AdminAuditEntity>): Map<Int, String> {
        val isus = entries.mapNotNull { isuIn(it.target, USER) }.toSet()
        if (isus.isEmpty()) return emptyMap()
        return users.findSummaryRowsByIsu(isus).mapNotNull { row -> row.name?.let { row.isu to it } }.toMap()
    }

    /** The ISU of a `user:<isu>` or `teacher:<isu>` target with that [prefix]. */
    private fun isuIn(target: String, prefix: String): Int? = target.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()

    private fun startOf(day: LocalDate) = day.atStartOfDay(AdminDashboardService.ZONE).toInstant()

    private companion object {
        const val USER = "user:"
        const val TEACHER = "teacher:"
    }
}
