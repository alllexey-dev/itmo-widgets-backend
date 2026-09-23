package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.exceptions.*
import dev.alllexey.itmowidgets.backend.repositories.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.Optional
import kotlin.test.*

internal object ModerationFixture {
    val now: Instant = Instant.parse("2026-09-22T09:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    fun user(isu: Int = 970001) = User(isu = isu, name = "Synthetic user", pictureUrl = null, createdAt = now).apply {
        settings = UserSettingsEntity(user = this)
    }
    /** A synthetic approved revision [revisionId] of a public link by [owner], as the queue shows it. */
    fun linkTarget(revisionId: UUID, owner: User): SubjectLinkTarget {
        val author = UserData(owner.isu, "Synthetic user", null, emptyList(), UserCapabilities(false, false, false))
        val linkId = UUID.randomUUID()
        val url = "https://example.org/materials"
        val revision = SubjectLinkRevision(revisionId, linkId, 1, LinkCategory.MATERIALS, url, "Материалы", LinkVisibility.ALL,
            LinkRevisionStatus.APPROVED, now, now, null)
        val link = SubjectLink(linkId, 42, "Предмет", "2026-1", LinkCategory.MATERIALS, url, "Материалы", LinkVisibility.ALL,
            null, SubjectLinkStatus.PUBLISHED, null, 0, 0, isMine = false, isSaved = false, reportedByMe = false,
            author = author, updatedAt = now)
        return SubjectLinkTarget(revision, link, author, emptyList(), SubmitterHistory(0, 0, 0, emptyList()))
    }

    fun case(reason: ModerationCaseReason = ModerationCaseReason.SUBMISSION) = ModerationCaseEntity(
        targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = UUID.randomUUID(), reason = reason, openedAt = now)
}

internal class FakeModerationTarget(val owner: User = ModerationFixture.user()) : ModerationTarget {
    var reportable = true
    val applied = mutableListOf<Pair<ModerationAction, ModerationDecisionEntity>>()
    override fun targetType() = ModerationTargetType.SUBJECT_RESOURCE
    override fun ownerId(targetId: UUID) = owner.id
    override fun isReportable(targetId: UUID, reporterId: UUID) = reportable
    override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) { applied += action to decision }
    override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget = ModerationFixture.linkTarget(targetId, owner)
}
