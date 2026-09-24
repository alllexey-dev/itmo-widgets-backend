package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant

enum class ReviewSyncOutcome { UNCHANGED, UPDATED, FAILED }

/** The provider's ETag, the running lease and the last run summary; the migration creates the only row. */
@Entity
@Table(name = "external_review_sync_state")
class ExternalReviewSyncStateEntity(
    @Id @Enumerated(EnumType.STRING) @Column(length = 32) val provider: ReviewProvider,
    @Column(length = 200) var etag: String? = null,
    var runningSince: Instant? = null,
    var lastCheckedAt: Instant? = null,
    var lastChangedAt: Instant? = null,
    var lastSuccessAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(length = 16) var lastOutcome: ReviewSyncOutcome? = null,
    @Column(length = 300) var lastError: String? = null,
    @Column(nullable = false) var lastAdded: Int = 0,
    @Column(nullable = false) var lastUpdated: Int = 0,
    @Column(nullable = false) var lastRemoved: Int = 0,
    @Column(nullable = false) var teachersTotal: Int = 0,
    @Column(nullable = false) var reviewsTotal: Int = 0,
)
