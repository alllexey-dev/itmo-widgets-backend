package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.core.model.GroupData
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One page of an admin list; [page] is zero-based, [total] counts every matching item. */
data class AdminPage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long) {
    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun request(page: Int, size: Int, sort: Sort = Sort.unsorted()): PageRequest {
            if (page < 0 || size !in 1..MAX_SIZE) throw InvalidRequestDataException("Invalid page")
            return PageRequest.of(page, size, sort)
        }

        fun <T> of(source: Page<*>, items: List<T>) = AdminPage(items, source.number, source.size, source.totalElements)
    }
}

/** Stored identity for admin lists: groups come from the ID token, highest course first. */
data class AdminUserSummary(val isu: Int, val name: String, val pictureUrl: String?, val groups: List<GroupData>)

/** Link state next to a reviewed revision; the content itself is in the revision. */
data class AdminLinkSummary(
    val id: UUID,
    val subjectId: Long,
    val subjectName: String,
    val periodKey: String,
    val score: Int,
    val hidden: Boolean,
)

/** A moderation queue row. Revision, link and author are null when the target was deleted. */
data class AdminCaseItem(
    val id: UUID,
    val targetType: ModerationTargetType,
    val status: ModerationCaseStatus,
    val reason: ModerationCaseReason,
    val openedAt: Instant,
    val resolvedAt: Instant?,
    val revision: SubjectLinkRevision?,
    val link: AdminLinkSummary?,
    val author: AdminUserSummary?,
    /** Active (not dismissed) reports on the target. */
    val reportCount: Long,
)

data class AdminRestriction(
    val id: UUID,
    val user: AdminUserSummary,
    val capability: RestrictionCapability,
    val reason: String,
    val startsAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val revokedByIsu: Int?,
    val active: Boolean,
    /** The case whose RESTRICT_USER decision created the restriction. */
    val caseId: UUID,
)

data class AdminUserItem(
    val isu: Int,
    val name: String,
    val pictureUrl: String?,
    val groups: List<GroupData>,
    val roles: List<String>,
    val createdAt: Instant,
)

data class AdminDevice(val name: String, val lastLogin: Instant)

/**
 * [user] carries current study groups when the MyITMO directory answers; [groups] is every group the
 * ID token ever listed. [lastSeen] is the latest device login or web session use.
 */
data class AdminUserDetail(
    val user: AdminUserSummary,
    val roles: List<String>,
    val groups: List<GroupData>,
    val createdAt: Instant,
    val devices: List<AdminDevice>,
    val friendsCount: Long,
    val linksCount: Long,
    val restrictions: List<AdminRestriction>,
    val lastSeen: Instant?,
)

/** Rolling windows end now; [links] has every [SubjectLinkStatus] key. */
data class AdminDashboardTotals(
    val users: Long,
    val newUsers7d: Long,
    val activeDevices7d: Long,
    val activeDevices30d: Long,
    val webSessions7d: Long,
    val friendships: Long,
    val links: Map<SubjectLinkStatus, Long>,
    val openCases: Long,
    val activeAutoSignEntries: Long,
    val activeFreeSignEntries: Long,
)

/** One Europe/Moscow calendar day. [activeDevices] counts devices whose latest login fell on that day. */
data class AdminDashboardDay(val date: LocalDate, val newUsers: Long, val activeDevices: Long, val createdLinks: Long)

/** [days] holds exactly 30 consecutive days, oldest first, ending today in Europe/Moscow. */
data class AdminDashboard(val totals: AdminDashboardTotals, val days: List<AdminDashboardDay>)

data class AdminSportRun(
    val id: Long,
    val timestamp: Instant,
    val outcome: SportUpdateOutcome,
    val durationMillis: Long,
    val receivedLessons: Int,
    val newLessonsAdded: Int,
    val updatedLessons: Int,
    val skippedLessons: Int,
    val errorCategory: SportUpdateErrorCategory?,
)

/** Catalog refresh health: the last 50 runs, seven-day totals and the live queue sizes. */
data class AdminSportStatus(
    val runs: List<AdminSportRun>,
    /** Every outcome key, zero when absent. */
    val outcomes7d: Map<SportUpdateOutcome, Long>,
    /** Every error category key, zero when absent. */
    val errors7d: Map<SportUpdateErrorCategory, Long>,
    val averageDurationMillis7d: Long?,
    val lastSuccessAt: Instant?,
    val activeAutoSignEntries: Long,
    val activeFreeSignEntries: Long,
)

/** The Android version metadata served by `/api/app/version-info`; [overridden] is true once stored in settings. */
data class AdminAppVersion(
    val latest: String,
    val minimum: String,
    val note: String,
    val overridden: Boolean,
    val updatedAt: Instant?,
)

data class AdminAppVersionRequest(val latest: String, val minimum: String, val note: String = "")

data class AdminAuditEntry(
    val id: UUID,
    val action: String,
    val target: String,
    val details: String?,
    val createdAt: Instant,
    val actorIsu: Int,
    val actorName: String,
)
