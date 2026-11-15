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
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminActivePeople
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAiSummaries
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersion
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAudienceDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAudienceStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAuditEntry
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCaseItem
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCaseStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClassicCohort
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClient
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientBuild
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientBuildGroup
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientKind
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientVersionWindow
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientVersions
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClients
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCourseCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboard
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardTotals
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDecisionStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDevice
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFacultyCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureUsage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminLinkSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminModerationStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminModerationStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPairingFunnel
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPeopleDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPrivacyStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPushStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReportStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRestriction
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRetentionCohort
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewVerification
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewsSync
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRouteStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServerDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServerStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServiceCredential
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSheetRuleError
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSheetRules
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportDayEntries
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportEntryStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportRun
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSectionStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSettings
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSettingsRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSlotStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStatus
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSubscriptionStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryHiddenRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryStatus
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminTeacherSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUpstreamStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserDetail
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserItem
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserSummary
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.credentials.model.CredentialSource
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatus
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStopReason
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import java.time.Instant
import java.time.LocalDate

/**
 * Synthetic values behind the admin fixtures. They record response shapes for Web and for the
 * Spring Boot 4 check; unlike [ContractSamples] they need not cover every enum value or nullable field.
 */
object AdminWebContractSamples {
    const val CREDENTIAL_VALUE = "synthetic-credential-value"

    private fun at(text: String): Instant = Instant.parse(text)

    private fun <T> page(items: List<T>) = AdminPage(items, 0, AdminPage.DEFAULT_SIZE, items.size.toLong())

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
        devices = listOf(
            AdminDevice(
                "Pixel 8 (synthetic)",
                at("2026-10-05T07:30:00Z"),
                "2.3.0-beta.1",
                20291,
                AppPlatform.ANDROID,
                "github",
                at("2026-10-05T08:10:00Z"),
            ),
            AdminDevice("iPhone 15 (synthetic)", at("2026-09-20T10:00:00Z"), appVersion = "2.3.0", platform = AppPlatform.IOS),
            AdminDevice("Galaxy A52 (synthetic)", at("2026-09-12T16:00:00Z")),
        ),
        friendsCount = 12, linksCount = 3,
        restrictions = listOf(activeRestriction), lastSeen = at("2026-10-05T07:30:00Z"), agentAccess = true,
        clients = listOf(
            AdminClient(
                ClientKind.ANDROID, null, "2.3.0", "20300", "play", "alllexey-dev/ITMO.Widgets", "abc1234", null, false, "36", null,
                at("2026-09-01T12:00:00Z"), at("2026-10-05T07:30:00Z"),
            ),
            AdminClient(
                ClientKind.AGENT, "Synthetic agent", "1.4.0", null, "custom", "local", null, null, true, "linux-arm64", null,
                at("2026-09-10T12:00:00Z"), at("2026-10-05T07:00:00Z"),
            ),
            AdminClient(
                ClientKind.WEB, null, "1a2b3c4", null, "web", null, "1a2b3c4", null, false, null, "chrome/131",
                at("2026-10-01T12:00:00Z"), at("2026-10-04T20:00:00Z"),
            ),
        ),
    )

    val grantedRoles = listOf("MODERATOR")
    val revokedRoles = emptyList<String>()

    // endregion users

    // region dashboard and system

    val dashboard = AdminDashboard(
        AdminDashboardTotals(
            users = 1200, newUsers7d = 35, activeDevices7d = 640, activeDevices30d = 910,
            devicesByPlatform = mapOf(AppPlatform.ANDROID to 1100L, AppPlatform.IOS to 60L), webSessions7d = 12,
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

    val sportSettings = AdminSportSettings(autoSignLimit = 10, overridden = false, updatedAt = null)
    val sportSettingsRequest = AdminSportSettingsRequest(autoSignLimit = 15)
    val updatedSportSettings = AdminSportSettings(autoSignLimit = 15, overridden = true, updatedAt = NOW)

    val appVersion = AdminAppVersion("2.2", "2.1", "Синтетическая заметка о версии", overridden = false, updatedAt = null)
    val appVersionRequest = AdminAppVersionRequest("2.2.1", "2.1", "")
    val updatedAppVersion = AdminAppVersion("2.2.1", "2.1", "", overridden = true, updatedAt = NOW)

    val clients = AdminClients(
        kinds = listOf(
            AdminClientKind(
                ClientKind.ANDROID,
                410,
                620,
                700,
                listOf(
                    AdminClientBuildGroup(false, "2.3.0", "play", "alllexey-dev/ITMO.Widgets", 500, 560),
                    AdminClientBuildGroup(true, "2.3.0", "custom", "someone/ITMO.Widgets", 3, 4),
                ),
                listOf(AdminClientCount("36", 400), AdminClientCount(null, 20)),
            ),
            AdminClientKind(
                ClientKind.WEB,
                12,
                30,
                41,
                listOf(AdminClientBuildGroup(false, "1a2b3c4", "web", null, 30, 41)),
                listOf(AdminClientCount("chrome/131", 30)),
            ),
        ),
        history = listOf(AdminClientDay(LocalDate.parse("2026-10-04"), ClientKind.ANDROID, false, "2.3.0", 400, 600, 690)),
    )

    val clientVersions = AdminClientVersions(
        last7d = AdminClientVersionWindow(
            activeDevices = 640,
            unknownDevices = 590,
            builds = listOf(
                AdminClientBuild(AppPlatform.ANDROID, "github", "2.3.0-beta.1", 20291, 38),
                AdminClientBuild(AppPlatform.ANDROID, "play", "2.3.0-beta.1", 20291, 9),
                AdminClientBuild(AppPlatform.IOS, "appstore", "2.3.0-beta.1", 20291, 3),
            ),
        ),
        last30d = AdminClientVersionWindow(
            activeDevices = 910,
            unknownDevices = 858,
            builds = listOf(
                AdminClientBuild(AppPlatform.ANDROID, "github", "2.3.0-beta.1", 20291, 40),
                AdminClientBuild(AppPlatform.ANDROID, "play", "2.3.0-beta.1", 20291, 9),
                AdminClientBuild(AppPlatform.IOS, "appstore", "2.3.0-beta.1", 20291, 3),
            ),
        ),
    )

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

    val sheetRules = AdminSheetRules(
        enabled = true,
        running = true,
        runningSince = Instant.parse("2026-10-05T08:58:00Z"),
        statuses = mapOf(
            SheetRuleStatus.PENDING to 2L,
            SheetRuleStatus.RUNNING to 1L,
            SheetRuleStatus.READY to 14L,
            SheetRuleStatus.INSUFFICIENT to 3L,
            SheetRuleStatus.FAILED to 4L,
        ),
        attempts = 9,
        lastAnalyzedAt = Instant.parse("2026-10-05T08:40:00Z"),
        errors = listOf(AdminSheetRuleError("closed", 3), AdminSheetRuleError("timeout", 1)),
        budgetDay = LocalDate.parse("2026-10-05"),
        budgetUsed = 6,
        dailyBudget = 20,
    )

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

    // region statistics

    val sportStats = AdminSportStats(
        from = LocalDate.parse("2026-10-04"),
        to = LocalDate.parse("2026-10-05"),
        days = listOf(
            AdminSportStatsDay(LocalDate.parse("2026-10-04"), AdminSportDayEntries(12, 7), AdminSportDayEntries(5, 2), signUps = 11),
            AdminSportStatsDay(LocalDate.parse("2026-10-05"), AdminSportDayEntries(0, 0), AdminSportDayEntries(3, 0), signUps = 4),
        ),
        autoSign = AdminSportEntryStats(
            created = 12,
            outcomes = SportEntryOutcome.entries.associateWith { if (it == SportEntryOutcome.SATISFIED) 7L else 1L },
            timed = 6,
            medianSeconds = 42,
            p90Seconds = 310,
        ),
        freeSign = AdminSportEntryStats(
            created = 8,
            outcomes = SportEntryOutcome.entries.associateWith { if (it == SportEntryOutcome.WAITING) 3L else 1L },
            timed = 0,
            medianSeconds = null,
            p90Seconds = null,
        ),
        subscriptions = AdminSportSubscriptionStats(
            active = 25,
            stopped = mapOf(SportSectionStopReason.USER to 4L, SportSectionStopReason.EXPIRED to 9L),
            created = 6,
            occurrences = mapOf(
                SportSectionOccurrenceState.QUEUED to 18L,
                SportSectionOccurrenceState.LIMIT to 2L,
                SportSectionOccurrenceState.SKIPPED to 1L,
            ),
        ),
        sections = listOf(
            AdminSportSectionStats("Плавание", autoSignEntries = 9, freeSignEntries = 6, satisfied = 8, medianWaitSeconds = 5400),
            AdminSportSectionStats("Настольный теннис", autoSignEntries = 3, freeSignEntries = 2, satisfied = 0, medianWaitSeconds = null),
        ),
        slots = listOf(
            AdminSportSlotStats(weekday = 1, timeStart = "08:20", entries = 7, satisfied = 5),
            AdminSportSlotStats(weekday = 7, timeStart = "18:30", entries = 5, satisfied = 2),
        ),
    )

    val moderationStats = AdminModerationStats(
        from = LocalDate.parse("2026-10-04"),
        to = LocalDate.parse("2026-10-05"),
        days = listOf(
            AdminModerationStatsDay(
                date = LocalDate.parse("2026-10-04"),
                newReviews = 3,
                reviewVotes = 12,
                externalReviewVotes = 5,
                linkVotes = 8,
                casesOpened = 4,
                casesResolved = 2,
                casesWithdrawn = 1,
                backlog = 6,
                reports = 2,
            ),
            AdminModerationStatsDay(LocalDate.parse("2026-10-05"), 0, 1, 0, 2, 1, 3, 0, 4, 0),
        ),
        ownReviews = 214,
        cases = listOf(
            AdminCaseStats(ModerationTargetType.SUBJECT_RESOURCE, ModerationCaseReason.SUBMISSION, 3, 3, 0, 1800, 7200),
            AdminCaseStats(ModerationTargetType.TEACHER_REVIEW, ModerationCaseReason.REPORTS, 2, 1, 1, 86400, 86400),
            AdminCaseStats(ModerationTargetType.TEACHER_REVIEW, ModerationCaseReason.SUBMISSION, 1, 0, 0, null, null),
        ),
        decisions = listOf(
            AdminDecisionStats(ModerationTargetType.SUBJECT_RESOURCE, ModerationAction.APPROVE, ModerationActor.POLICY, 2),
            AdminDecisionStats(ModerationTargetType.TEACHER_REVIEW, ModerationAction.HIDE, ModerationActor.MODERATOR, 1),
        ),
        reports = listOf(AdminReportStats(ModerationTargetType.TEACHER_REVIEW, ReportReason.OFFENSIVE, total = 2, dismissed = 1)),
    )

    val featureStats = AdminFeatureStats(
        from = LocalDate.parse("2026-10-04"),
        to = LocalDate.parse("2026-10-05"),
        users = 1520,
        usersWithFriends = 610,
        socialUsers = 230,
        features = listOf(
            AdminFeatureUsage("FRIENDS", events = 41, users = 70),
            AdminFeatureUsage("SCHEDULE", events = null, users = 1105),
            AdminFeatureUsage("SPORT_AUTO_SIGN", events = 12, users = 9),
        ),
        days = listOf(
            AdminFeatureStatsDay(
                date = LocalDate.parse("2026-10-04"),
                friendRequests = 25,
                friendAccepts = 18,
                connectionsCreated = mapOf(ClientType.AGENT to 1L, ClientType.MCP to 0L, ClientType.WEB to 6L),
                connectionsRevoked = mapOf(ClientType.AGENT to 0L, ClientType.MCP to 1L, ClientType.WEB to 2L),
            ),
            AdminFeatureStatsDay(
                date = LocalDate.parse("2026-10-05"),
                friendRequests = 16,
                friendAccepts = 11,
                connectionsCreated = ClientType.entries.associateWith { 0L },
                connectionsRevoked = ClientType.entries.associateWith { 0L },
            ),
        ),
        privacy = AdminPrivacyStats(
            schedule = mapOf(SharingVisibility.ALL to 120L, SharingVisibility.FRIENDS to 1350L, SharingVisibility.NOBODY to 50L),
            sport = mapOf(SharingVisibility.ALL to 80L, SharingVisibility.FRIENDS to 1400L, SharingVisibility.NOBODY to 40L),
            friends = mapOf(SharingVisibility.ALL to 1300L, SharingVisibility.FRIENDS to 180L, SharingVisibility.NOBODY to 40L),
        ),
        pairing = listOf(
            AdminPairingFunnel(ClientType.AGENT, created = 4, opened = 3, approved = 3, claimed = 2, expired = 1),
            AdminPairingFunnel(ClientType.MCP, created = 0, opened = 0, approved = 0, claimed = 0, expired = 0),
            AdminPairingFunnel(ClientType.WEB, created = 31, opened = 29, approved = 27, claimed = 27, expired = 2),
        ),
    )

    val audienceStats = AdminAudienceStats(
        from = LocalDate.parse("2026-10-04"),
        to = LocalDate.parse("2026-10-05"),
        usersBefore = 1490,
        days = listOf(AdminAudienceDay(LocalDate.parse("2026-10-04"), 18), AdminAudienceDay(LocalDate.parse("2026-10-05"), 12)),
        activePeople = AdminActivePeople(active1d = 410, active7d = 820, active30d = 1130),
        peopleHistory = listOf(AdminPeopleDay(LocalDate.parse("2026-10-04"), active1d = 395, active7d = 812, active30d = 1121)),
        retention = listOf(
            AdminRetentionCohort(LocalDate.parse("2026-09-21"), size = 64, retained = listOf(51, 47)),
            AdminRetentionCohort(LocalDate.parse("2026-09-28"), size = 40, retained = listOf(33)),
        ),
        retentionClassic = listOf(
            AdminClassicCohort(LocalDate.parse("2026-09-21"), size = 64, active = listOf(null, 44)),
            AdminClassicCohort(LocalDate.parse("2026-09-28"), size = 40, active = listOf(38)),
        ),
        faculties = listOf(
            AdminFacultyCount("ФПИиКТ", "Факультет программной инженерии и компьютерной техники", users = 820, active30d = 640),
            AdminFacultyCount(null, null, users = 95, active30d = 20),
        ),
        courses = listOf(AdminCourseCount(1, users = 400, active30d = 330), AdminCourseCount(null, users = 95, active30d = 20)),
    )

    val serverStats = AdminServerStats(
        from = LocalDate.parse("2026-10-04"),
        to = LocalDate.parse("2026-10-05"),
        collectedSince = LocalDate.parse("2026-10-05"),
        days = listOf(
            AdminServerDay(LocalDate.parse("2026-10-04"), 0, 0, null, null, 0, 0, 0, 0, 0),
            AdminServerDay(
                date = LocalDate.parse("2026-10-05"),
                requests = 48200,
                errors5xx = 12,
                p50Millis = 18,
                p95Millis = 240,
                myItmoCalls = 3100,
                myItmoFailures = 25,
                pushSent = 920,
                pushFailed = 3,
                pushTokenGone = 7,
            ),
        ),
        routes = listOf(AdminRouteStats("sport", requests = 21000, errors4xx = 140, errors5xx = 9, p50Millis = 22, p95Millis = 310)),
        myItmo = listOf(
            AdminUpstreamStats(
                "sportSchedule",
                calls = 2400,
                failures = mapOf(
                    MyItmoFailureKind.AUTH to 0L,
                    MyItmoFailureKind.HTTP to 4L,
                    MyItmoFailureKind.NETWORK to 15L,
                    MyItmoFailureKind.MAPPING to 0L,
                ),
            ),
        ),
        push = listOf(AdminPushStats("SPORT_FREE_SIGN_LESSONS", sent = 610, failed = 2, tokenGone = 5, noDevice = 40)),
    )

    // endregion statistics

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
