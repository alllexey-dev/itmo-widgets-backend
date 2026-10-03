package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalReviewSyncStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncService
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(AdminReviewsService::class, AdminAccess::class, AdminReviewsServiceTest.TestConfig::class)
@TestPropertySource(properties = ["itmowidgets.reviews-sync.enabled=true"])
class AdminReviewsServiceTest @Autowired constructor(
    private val service: AdminReviewsService,
    private val states: ExternalReviewSyncStateRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var syncService: ReviewsSyncService

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewsSyncConfig::class)
    class TestConfig

    private lateinit var admin: User
    private lateinit var moderator: User

    @BeforeEach
    fun fixture() {
        admin = user(963001)
        moderator = user(963002)
        em.persist(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
    }

    @Test
    fun `the sync view counts stored reviews and shows the last run`() {
        review(1, 100001)
        review(2, 100001)
        review(3, 100002)
        review(4, 100003, removedAt = NOW)
        states.findById(ReviewProvider.REVIEWS_WORK_GD).orElseThrow().apply {
            lastCheckedAt = NOW
            lastChangedAt = NOW.minusSeconds(3_600)
            lastSuccessAt = NOW
            lastOutcome = ReviewSyncOutcome.UPDATED
            lastAdded = 3
            lastUpdated = 1
            lastRemoved = 1
            teachersTotal = 3
            reviewsTotal = 3
        }
        em.flush(); em.clear()

        val view = service.sync(admin.id)

        assertTrue(view.enabled)
        assertFalse(view.running)
        assertNull(view.runningSince)
        assertEquals(NOW, view.lastCheckedAt)
        assertEquals(NOW.minusSeconds(3_600), view.lastChangedAt)
        assertEquals(ReviewSyncOutcome.UPDATED, view.lastOutcome)
        assertNull(view.lastError)
        assertEquals(listOf(3, 1, 1), listOf(view.lastAdded, view.lastUpdated, view.lastRemoved))
        assertEquals(listOf(3, 3), listOf(view.upstreamTeachers, view.upstreamReviews))
        assertEquals(listOf(4L, 3L, 1L, 2L), listOf(view.reviewsTotal, view.reviewsActive, view.reviewsRemoved, view.teachersActive))
    }

    @Test
    fun `starting a sync delegates to the manual start and returns the running state`() {
        doAnswer {
            states.claim(ReviewProvider.REVIEWS_WORK_GD, NOW, NOW.minusSeconds(21_600))
            null
        }.`when`(syncService).startManual(admin.id)

        val view = service.startSync(admin.id)

        verify(syncService).startManual(admin.id)
        assertTrue(view.running)
        assertEquals(NOW, view.runningSince)
    }

    @Test
    fun `verification counts own reviews by the state of their ISU check for admins only`() {
        val before = service.verification(admin.id)
        val author = user(963003)
        ownReview(author, 100001, ReviewVerification.PENDING)
        ownReview(author, 100002, ReviewVerification.PENDING)
        ownReview(author, 100003, ReviewVerification.VERIFIED)
        ownReview(author, 100004, ReviewVerification.UNVERIFIED)
        em.flush(); em.clear()

        val after = service.verification(admin.id)

        assertEquals(listOf(2L, 1L, 1L), listOf(after.pending - before.pending, after.verified - before.verified,
            after.unverified - before.unverified))
        assertFailsWith<PermissionDeniedException> { service.verification(moderator.id) }
    }

    @Test
    fun `a moderator can neither view nor start the sync`() {
        assertFailsWith<PermissionDeniedException> { service.sync(moderator.id) }
        assertFailsWith<PermissionDeniedException> { service.startSync(moderator.id) }
        verify(syncService, never()).startManual(moderator.id)
    }

    private fun user(isu: Int) = em.persist(User(isu = isu, name = "Synthetic user", pictureUrl = null, createdAt = NOW).apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun review(externalId: Long, teacherIsu: Int, removedAt: Instant? = null) = em.persist(ExternalTeacherReviewEntity(
        provider = ReviewProvider.REVIEWS_WORK_GD, externalId = externalId, teacherIsu = teacherIsu,
        teacherName = "Synthetic teacher", subjectTitle = null, sourceTitle = null, sourceLink = null,
        dateRaw = "", writtenOn = null, writtenBeforeYear = null,
        text = "Synthetic review", firstSeenAt = NOW.minusSeconds(86_400), lastSeenAt = NOW, removedAt = removedAt,
    ))

    private fun ownReview(author: User, teacherIsu: Int, verification: ReviewVerification) = em.persist(TeacherReviewEntity(
        author = author, teacherIsu = teacherIsu, subjectTitle = null, text = "Синтетический отзыв о преподавателе для теста",
        verification = verification, verifiedFlowId = 93724L.takeIf { verification == ReviewVerification.VERIFIED },
        verificationDueAt = NOW.takeIf { verification == ReviewVerification.PENDING }, createdAt = NOW, updatedAt = NOW,
    ))

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
    }
}
