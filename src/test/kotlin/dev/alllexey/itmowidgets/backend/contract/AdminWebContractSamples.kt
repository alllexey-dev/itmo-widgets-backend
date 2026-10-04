package dev.alllexey.itmowidgets.backend.contract

import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CASE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CHALLENGE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.FRIEND_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.LINK_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.NOW
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PERIOD
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.RESTRICTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.REVIEW_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.SUBJECT_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.TEACHER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.uuid
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditAction
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAiSummaries
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersion
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAuditEntry
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCaseItem
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboard
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardTotals
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDevice
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminLinkSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRestriction
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewVerification
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewsSync
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServiceCredential
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportRun
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStatus
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryHiddenRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryStatus
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminTeacherSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserDetail
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserItem
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserSummary
import dev.alllexey.itmowidgets.backend.feature.credentials.model.CredentialSource
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.CreatedChallenge
import java.time.Instant
import java.time.LocalDate

/**
 * Synthetic values behind the admin and web sign-in fixtures. They record response shapes for Web and for the
 * Spring Boot 4 check; unlike [ContractSamples] they need not cover every enum value or nullable field.
 */
object AdminWebContractSamples {
    const val POLL_SECRET = "synthetic-poll-secret"
    const val SESSION_TOKEN = "synthetic-session-token"
    const val USER_AGENT = "Synthetic browser"
    const val CREDENTIAL_VALUE = "synthetic-credential-value"

    private fun at(text: String): Instant = Instant.parse(text)

    private fun <T> page(items: List<T>) = AdminPage(items, 0, AdminPage.DEFAULT_SIZE, items.size.toLong())

    // region web sign-in

    val challenge = CreatedChallenge(CHALLENGE_ID, "ABCD2345", POLL_SECRET, at("2026-10-05T09:05:00Z"))

    // endregion web sign-in

    // region users

    private val author =
        AdminUserSummary(FRIEND_ISU, "Друг Первый", "https://example.org/avatars/$FRIEND_ISU.jpg", ContractSamples.me.groups)
    private val unnamed = AdminUserSummary(100003, "Студент Третий", null, emptyList())

    private val activeRestriction = AdminRestriction(
        id = RESTRICTION_ID, user = author, capability = RestrictionCapability.REPORT, reason = "Синтетическая причина",
        startsAt = at("2026-10-01T10:00:00Z"), expiresAt = at("2026-10-31T10:00:00Z"), revokedAt = null, revokedByIsu = null,
        active = true, caseId = CASE_ID,
    )
    private val revokedRestriction = AdminRestriction(
        id = uuid(402), user = unnamed, capability = RestrictionCapability.SUBMIT_RESOURCES, reason = "Синтетическое ограничение",
        startsAt = at("2026-09-20T10:00:00Z"), expiresAt = null, revokedAt = at("2026-09-25T10:00:00Z"), revokedByIsu = VIEWER_ISU,
        active = false, caseId = uuid(302),
    )

    val users = page(
        listOf(
            AdminUserItem(VIEWER_ISU, "Студент Тестовый", null, emptyList(), listOf("ADMIN", "MODERATOR"), NOW),
            AdminUserItem(FRIEND_ISU, author.name, author.pictureUrl, author.groups, emptyList(), at("2026-09-01T12:00:00Z")),
        ),
    )

    val user = AdminUserDetail(
        user = author, roles = listOf("MODERATOR"), groups = author.groups, createdAt = at("2026-09-01T12:00:00Z"),
        devices = listOf(AdminDevice("Pixel 8 (synthetic)", at("2026-10-05T07:30:00Z"))), friendsCount = 12, linksCount = 3,
        restrictions = listOf(activeRestriction), lastSeen = at("2026-10-05T07:30:00Z"),
    )

    val grantedRoles = listOf("MODERATOR")
    val revokedRoles = emptyList<String>()

    // endregion users

    // region dashboard and system

    val dashboard = AdminDashboard(
        AdminDashboardTotals(
            users = 1200, newUsers7d = 35, activeDevices7d = 640, activeDevices30d = 910, webSessions7d = 12,
            friendships = 2300, links = SubjectLinkStatus.entries.associateWith { (it.ordinal + 1) * 10L }, openCases = 4,
            activeAutoSignEntries = 17, activeFreeSignEntries = 9,
        ),
        listOf(
            AdminDashboardDay(LocalDate.parse("2026-10-04"), newUsers = 5, activeDevices = 300, createdLinks = 2),
            AdminDashboardDay(LocalDate.parse("2026-10-05"), newUsers = 3, activeDevices = 280, createdLinks = 0),
        ),
    )

    val sport = AdminSportStatus(
        runs = listOf(
            AdminSportRun(2, at("2026-10-05T08:50:00Z"), SportUpdateOutcome.SUCCESS, 1830, 120, 2, 5, 0, null),
            AdminSportRun(1, at("2026-10-05T08:40:00Z"), SportUpdateOutcome.FAILED, 410, 0, 0, 0, 0, SportUpdateErrorCategory.AUTH),
        ),
        outcomes7d = SportUpdateOutcome.entries.associateWith { it.ordinal * 7L },
        errors7d = SportUpdateErrorCategory.entries.associateWith { if (it == SportUpdateErrorCategory.AUTH) 1L else 0L },
        averageDurationMillis7d = 1650,
        lastSuccessAt = at("2026-10-05T08:50:00Z"),
        activeAutoSignEntries = 17,
        activeFreeSignEntries = 9,
    )

    val appVersion = AdminAppVersion("2.2", "2.1", "Синтетическая заметка о версии", overridden = false, updatedAt = null)
    val appVersionRequest = AdminAppVersionRequest("2.2.1", "2.1", "")
    val updatedAppVersion = AdminAppVersion("2.2.1", "2.1", "", overridden = true, updatedAt = NOW)

    val credentials = ServiceCredential.entries.map { key ->
        val present = key != ServiceCredential.GEMINI_API_KEY
        AdminServiceCredential(
            key = key, kind = key.kind, replaceable = key.replaceable, present = present,
            status = if (present) ServiceCredentialStatus.OK else ServiceCredentialStatus.MISSING,
            expiresAt = if (key.expiresSoonWithin != null) at("2026-10-06T09:00:00Z") else null,
            expiresSoon = key == ServiceCredential.MY_ITMO_REFRESH_TOKEN,
            lastUsedAt = if (present) at("2026-10-05T08:50:00Z") else null,
            lastRenewedAt = if (key in ServiceCredential.MY_ITMO) at("2026-10-05T08:00:00Z") else null,
            lastErrorAt = if (key == ServiceCredential.ISU_KEYCLOAK_IDENTITY) at("2026-10-04T08:00:00Z") else null,
            lastError = if (key == ServiceCredential.ISU_KEYCLOAK_IDENTITY) "EXPIRED login" else null,
            updatedAt = at("2026-10-05T08:00:00Z"),
            updatedSource = if (present) CredentialSource.ROTATION else null,
            updatedByIsu = if (key == ServiceCredential.ISU_KEYCLOAK_IDENTITY) VIEWER_ISU else null,
            updatedByName = if (key == ServiceCredential.ISU_KEYCLOAK_IDENTITY) "Студент Тестовый" else null,
        )
    }

    // endregion dashboard and system

    // region moderation

    private val revision = SubjectLinkRevision(
        id = uuid(112), linkId = LINK_ID, number = 2, category = LinkCategory.MATERIALS, url = "https://example.org/links/new",
        title = "Конспекты лекций", visibility = LinkVisibility.ALL, flowId = null, status = LinkRevisionStatus.PENDING,
        submittedAt = at("2026-10-02T07:00:00Z"), decidedAt = null, note = null,
    )

    val cases = page(
        listOf(
            AdminCaseItem(
                id = CASE_ID, targetType = ModerationTargetType.SUBJECT_RESOURCE, status = ModerationCaseStatus.OPEN,
                reason = ModerationCaseReason.SUBMISSION, openedAt = at("2026-10-02T07:00:00Z"), resolvedAt = null,
                revision = revision,
                link = AdminLinkSummary(LINK_ID, SUBJECT_ID, "Математический анализ", PERIOD, score = 3, hidden = false),
                review = null, author = author, reportCount = 4,
            ),
            AdminCaseItem(
                id = uuid(302), targetType = ModerationTargetType.TEACHER_REVIEW, status = ModerationCaseStatus.RESOLVED,
                reason = ModerationCaseReason.REPORTS, openedAt = at("2026-09-28T07:00:00Z"), resolvedAt = at("2026-09-28T09:00:00Z"),
                revision = null, link = null,
                review = AdminReviewSummary(
                    REVIEW_ID,
                    TEACHER_ISU,
                    "Математический анализ",
                    "Синтетический отзыв под именем.",
                    score = -4,
                    hidden = true,
                    anonymous = true,
                ),
                author = author, reportCount = 2,
            ),
            AdminCaseItem(
                id = uuid(303), targetType = ModerationTargetType.SUBJECT_RESOURCE, status = ModerationCaseStatus.WITHDRAWN,
                reason = ModerationCaseReason.VOTES, openedAt = at("2026-09-27T07:00:00Z"), resolvedAt = at("2026-09-27T08:00:00Z"),
                revision = null, link = null, review = null, author = null, reportCount = 0,
            ),
        ),
    )

    val restrictions = page(listOf(activeRestriction, revokedRestriction))

    // endregion moderation

    // region reviews

    val reviewsSync = AdminReviewsSync(
        enabled = true, running = false, runningSince = null, lastCheckedAt = at("2026-10-05T06:00:00Z"),
        lastChangedAt = at("2026-10-04T06:00:00Z"), lastSuccessAt = at("2026-10-05T06:00:00Z"),
        lastOutcome = ReviewSyncOutcome.UPDATED, lastError = null, lastAdded = 3, lastUpdated = 5, lastRemoved = 1,
        upstreamTeachers = 820, upstreamReviews = 4100, reviewsTotal = 4300, reviewsActive = 4100, reviewsRemoved = 200,
        teachersActive = 790,
    )

    val startedReviewsSync = reviewsSync.copy(
        running = true,
        runningSince = NOW,
        lastOutcome = ReviewSyncOutcome.FAILED,
        lastError = "HTTP 503 /teacher/100123",
    )

    val verification = AdminReviewVerification(pending = 2, verified = 40, unverified = 3)

    val aiSummaries = AdminAiSummaries(
        enabled = true, running = false, runningSince = null, model = "synthetic-model", keyStatus = ServiceCredentialStatus.OK,
        lastStartedAt = at("2026-10-05T03:00:00Z"), lastFinishedAt = at("2026-10-05T03:10:00Z"),
        lastTrigger = SummaryRunTrigger.SCHEDULE, lastOutcome = SummaryRunOutcome.COMPLETED, lastError = null, lastGenerated = 12,
        lastFailed = 1, lastRequests = 14, ready = 300, pending = 20, failed = 2, hidden = 3,
        budgetDay = LocalDate.parse("2026-10-05"), budgetUsed = 14, dailyBudget = 250,
    )

    val startedAiSummaries = aiSummaries.copy(
        running = true,
        runningSince = NOW,
        lastTrigger = SummaryRunTrigger.ADMIN,
        lastOutcome = SummaryRunOutcome.RATE_LIMITED,
        lastError = "RATE_LIMITED 429",
    )

    private val readySummary = AdminTeacherSummary(
        teacherIsu = TEACHER_ISU, teacherName = "Преподаватель Тестовый", status = AdminSummaryStatus.READY, inputCount = 7,
        reviewCount = 7, summary = ContractSamples.teacherReviews.summary, hidden = false, hiddenAt = null, hiddenByName = null,
        attempts = 1, lastAttemptAt = at("2026-10-04T03:00:00Z"), lastError = null,
    )

    val summaryTeachers = page(
        listOf(
            readySummary,
            AdminTeacherSummary(
                teacherIsu = TEACHER_ISU + 1, teacherName = null, status = AdminSummaryStatus.FAILED, inputCount = 4,
                reviewCount = null, summary = null, hidden = false, hiddenAt = null, hiddenByName = null, attempts = 3,
                lastAttemptAt = at("2026-10-05T03:05:00Z"), lastError = "SCHEMA scales",
            ),
        ),
    )

    val hideSummary = AdminSummaryHiddenRequest(hidden = true)
    val hiddenSummary = readySummary.copy(
        status = AdminSummaryStatus.HIDDEN,
        hidden = true,
        hiddenAt = NOW,
        hiddenByName = "Студент Тестовый",
    )
    val regeneratedSummary = readySummary.copy(status = AdminSummaryStatus.PENDING, inputCount = 8)

    // endregion reviews

    val audit = page(
        listOf(
            AdminAuditEntry(
                uuid(601),
                AdminAuditAction.ROLE_GRANTED.name,
                "user:$FRIEND_ISU",
                "role MODERATOR",
                NOW,
                VIEWER_ISU,
                "Студент Тестовый",
            ),
            AdminAuditEntry(
                uuid(602),
                AdminAuditAction.AI_SUMMARY_HIDDEN.name,
                "teacher:$TEACHER_ISU",
                null,
                at("2026-10-04T12:00:00Z"),
                VIEWER_ISU,
                "Студент Тестовый",
            ),
        ),
    )
}
