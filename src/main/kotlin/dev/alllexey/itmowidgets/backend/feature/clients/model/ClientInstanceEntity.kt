package dev.alllexey.itmowidgets.backend.feature.clients.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * One client of a user: an app install or a connection ([instanceId]), with what its `X-Client` header last said.
 * [itmoSid] is an app install's own ITMO.ID session, from its ID-token sync. Written only by [dev.alllexey.itmowidgets.backend.feature.clients.persistence.ClientInstanceRepository.upsert].
 */
@Entity
@Table(name = "client_instances")
class ClientInstanceEntity(
    @Id val id: UUID,
    @Column(nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val kind: ClientKind,
    @Column(nullable = false) val instanceId: UUID,
    @Column(nullable = false, length = 64) val version: String,
    @Column(length = 64) val build: String?,
    @Column(length = 16) val channel: String?,
    @Column(length = 64) val source: String?,
    @Column(name = "commit_sha", length = 64) val commit: String?,
    @Column(length = 64) val base: String?,
    @Column(length = 64) val os: String?,
    @Column(length = 64) val browser: String?,
    @Column(nullable = false) val custom: Boolean,
    @Column(nullable = false) val firstSeenAt: Instant,
    @Column(nullable = false) val lastSeenAt: Instant,
    @Column(length = 64) val itmoSid: String? = null,
)
