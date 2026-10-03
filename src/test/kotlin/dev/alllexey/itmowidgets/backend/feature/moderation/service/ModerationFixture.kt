package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLink
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.SubmitterHistory
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.*

internal object ModerationFixture {
    val now: Instant = Instant.parse("2026-09-22T09:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    const val OWNER_ISU = 970001
    const val MODERATOR_ISU = 970002

    /** A synthetic approved revision [revisionId] of a public link by [owner], as the queue shows it. */
    fun linkTarget(revisionId: UUID, owner: User): SubjectLinkTarget {
        val author = UserData(owner.isu, "Synthetic user", null, emptyList(), UserCapabilities(false, false, false))
        val linkId = UUID.randomUUID()
        val url = "https://example.org/materials"
        val revision = SubjectLinkRevision(
            revisionId, linkId, 1, LinkCategory.MATERIALS, url, "Материалы", LinkVisibility.ALL,
            null, LinkRevisionStatus.APPROVED, now, now, null,
        )
        val link = SubjectLink(
            linkId, 42, "Предмет", "2026-1", LinkCategory.MATERIALS, url, "Материалы", LinkVisibility.ALL,
            null, null, SubjectLinkStatus.PUBLISHED, null, 0, 0, isMine = false, reportedByMe = false,
            author = author, updatedAt = now,
        )
        return SubjectLinkTarget(revision, link, author, emptyList(), SubmitterHistory(0, 0, 0, emptyList()))
    }

    fun case(reason: ModerationCaseReason = ModerationCaseReason.SUBMISSION) = ModerationCaseEntity(
        targetType = ModerationTargetType.SUBJECT_RESOURCE,
        targetId = UUID.randomUUID(),
        reason = reason,
        openedAt = now,
    )
}

internal class FakeModerationTarget(val owner: User = TestUsers.user(ModerationFixture.OWNER_ISU, createdAt = ModerationFixture.now)) :
    ModerationTarget {
    var reportable = true
    val applied = mutableListOf<Pair<ModerationAction, ModerationDecisionEntity>>()
    override fun targetType() = ModerationTargetType.SUBJECT_RESOURCE
    override fun ownerId(targetId: UUID) = owner.id
    override fun isReportable(targetId: UUID, reporterId: UUID) = reportable
    override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) {
        applied += action to decision
    }
    override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget = ModerationFixture.linkTarget(targetId, owner)
}
