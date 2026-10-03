package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewVerification
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewsSync
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalReviewSyncStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Admin-only view and manual start of the Reviews sync, and the counters of the ISU check of own reviews. */
@Service
class AdminReviewsService(
    private val access: AdminAccess,
    private val reviews: ExternalTeacherReviewRepository,
    private val states: ExternalReviewSyncStateRepository,
    private val syncService: ReviewsSyncService,
    private val config: ReviewsSyncConfig,
    private val ownReviews: TeacherReviewRepository,
) {
    @Transactional(readOnly = true)
    fun sync(adminId: UUID): AdminReviewsSync {
        access.requireAdmin(adminId)
        val provider = ReviewsSyncStore.PROVIDER
        val state = states.findById(provider).orElseThrow()
        return AdminReviewsSync(
            enabled = config.enabled,
            running = state.runningSince != null,
            runningSince = state.runningSince,
            lastCheckedAt = state.lastCheckedAt,
            lastChangedAt = state.lastChangedAt,
            lastSuccessAt = state.lastSuccessAt,
            lastOutcome = state.lastOutcome,
            lastError = state.lastError,
            lastAdded = state.lastAdded,
            lastUpdated = state.lastUpdated,
            lastRemoved = state.lastRemoved,
            upstreamTeachers = state.teachersTotal,
            upstreamReviews = state.reviewsTotal,
            reviewsTotal = reviews.countByProvider(provider),
            reviewsActive = reviews.countByProviderAndRemovedAtIsNull(provider),
            reviewsRemoved = reviews.countByProviderAndRemovedAtIsNotNull(provider),
            teachersActive = reviews.countActiveTeachers(provider),
        )
    }

    /** Starts a run in the background and returns the state with the lease already taken. */
    fun startSync(adminId: UUID): AdminReviewsSync {
        access.requireAdmin(adminId)
        syncService.startManual(adminId)
        return sync(adminId)
    }

    @Transactional(readOnly = true)
    fun verification(adminId: UUID): AdminReviewVerification {
        access.requireAdmin(adminId)
        return AdminReviewVerification(
            pending = ownReviews.countByVerification(ReviewVerification.PENDING),
            verified = ownReviews.countByVerification(ReviewVerification.VERIFIED),
            unverified = ownReviews.countByVerification(ReviewVerification.UNVERIFIED),
        )
    }
}
