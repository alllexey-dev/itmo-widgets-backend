package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowReaders
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkViews
import dev.alllexey.itmowidgets.backend.feature.links.web.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewViews
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryViews
import dev.alllexey.itmowidgets.backend.feature.schedule.service.ScheduleFlowMembership
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** A revert and a new decision on the same case, committed from two threads; the context is [ModerationRevertTest]'s. */
@Import(
    SubjectLinkService::class, SubjectLinkViews::class, FlowReaders::class, ScheduleFlowMembership::class, UserPrivacyService::class,
    TeacherReviewService::class, TeacherReviewViews::class, TeacherSummaryViews::class, RestrictionService::class,
    ModerationSettingsService::class, ModerationService::class, ModerationReportService::class, ModerationTargets::class,
    ModeratorAccess::class, AdminAccess::class, ModerationRevertTest.TimeConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModerationRevertConcurrencyTest @Autowired constructor(
    private val moderation: ModerationService,
    private val links: SubjectLinkService,
    private val clock: MutableClock,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService

    @Test
    fun `a revert racing a new decision on the same case ends as if one of them ran first`() {
        clock.now = START
        val judge = jdbc.insertUser(967901, createdAt = START)
        val author = jdbc.insertUser(967902, createdAt = START)
        jdbc.update("INSERT INTO user_roles (user_id, role, granted_at) VALUES (?, 'MODERATOR', ?)", judge, Timestamp.from(START))
        val link = UUID.randomUUID()
        try {
            links.save(
                author,
                link,
                SaveSubjectLinkRequest(
                    42,
                    "Предмет",
                    "2026-1",
                    LinkCategory.MATERIALS,
                    "https://example.org/race",
                    null,
                    LinkVisibility.ALL,
                    null,
                ),
            )
            val revision = jdbc.queryForObject("SELECT id FROM subject_link_revisions WHERE link_id = ?", UUID::class.java, link)!!
            val case = jdbc.queryForObject("SELECT id FROM moderation_cases WHERE target_id = ?", UUID::class.java, revision)!!
            val approval = moderation.decide(judge, case, ModerationDecisionRequest(ModerationAction.APPROVE)).decisions.last().id
            clock.now = START.plusSeconds(60)

            val executor = Executors.newFixedThreadPool(2)
            val barrier = CyclicBarrier(2)
            val (reverted, restored) = try {
                listOf(
                    executor.submit<Result<ModerationCase>> {
                        barrier.await(10, TimeUnit.SECONDS)
                        runCatching { moderation.revert(judge, case, approval) }
                    },
                    executor.submit<Result<ModerationCase>> {
                        barrier.await(10, TimeUnit.SECONDS)
                        runCatching { moderation.decide(judge, case, ModerationDecisionRequest(ModerationAction.RESTORE)) }
                    },
                ).map { it.get(20, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }

            // A RESTORE is taken on an open case and appended to a resolved one alike.
            assertTrue(restored.isSuccess, restored.exceptionOrNull()?.toString())
            val status = jdbc.queryForObject("SELECT status FROM subject_link_revisions WHERE id = ?", String::class.java, revision)
            val reverts = jdbc.queryForObject(
                "SELECT count(*) FROM moderation_decision_reverts WHERE decision_id = ?",
                Int::class.java,
                approval,
            )
            if (reverted.isSuccess) {
                // The revert ran first: the approval is undone, and the RESTORE resolved the reopened case.
                assertEquals("PENDING", status)
                assertEquals(1, reverts)
            } else {
                // The RESTORE ran first: the approval is no longer the latest decision, and nothing was undone.
                assertEquals(
                    "Only the latest decision of a case can be reverted",
                    assertIs<BusinessRuleException>(reverted.exceptionOrNull()).message,
                )
                assertEquals("APPROVED", status)
                assertEquals(0, reverts)
            }
            assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM moderation_cases WHERE id = ?", String::class.java, case))
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM moderation_decisions WHERE case_id = ?", Int::class.java, case))
        } finally {
            val cases = "SELECT c.id FROM moderation_cases c JOIN subject_link_revisions r ON r.id = c.target_id WHERE r.link_id = ?"
            jdbc.update(
                "DELETE FROM moderation_decision_reverts WHERE decision_id IN (SELECT id FROM moderation_decisions WHERE case_id IN ($cases))",
                link,
            )
            jdbc.update("DELETE FROM moderation_decisions WHERE case_id IN ($cases)", link)
            jdbc.update("DELETE FROM moderation_cases WHERE id IN ($cases)", link)
            jdbc.update("DELETE FROM subject_links WHERE id = ?", link)
            for (user in listOf(judge, author)) {
                jdbc.update("DELETE FROM user_roles WHERE user_id = ?", user)
                jdbc.update("DELETE FROM user_settings WHERE user_id = ?", user)
                jdbc.update("DELETE FROM users WHERE id = ?", user)
            }
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-24T09:00:00Z")
    }
}
