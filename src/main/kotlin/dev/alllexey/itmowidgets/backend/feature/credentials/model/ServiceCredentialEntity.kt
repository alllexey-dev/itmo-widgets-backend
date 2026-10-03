package dev.alllexey.itmowidgets.backend.feature.credentials.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** One secret of an external service; the migration creates every row, the value itself never leaves the store. */
@Entity
@Table(name = "service_credentials")
class ServiceCredentialEntity(
    @Id @Column(name = "key", length = 64) val key: String,
    @Column(columnDefinition = "text") var value: String? = null,
    var expiresAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var status: ServiceCredentialStatus = ServiceCredentialStatus.MISSING,
    var lastUsedAt: Instant? = null,
    var lastRenewedAt: Instant? = null,
    var lastErrorAt: Instant? = null,
    @Column(length = 300) var lastError: String? = null,
    @Column(nullable = false) var updatedAt: Instant,
    var updatedBy: UUID? = null,
    @Enumerated(EnumType.STRING) @Column(length = 16) var updatedSource: CredentialSource? = null,
) {
    val credential: ServiceCredential get() = ServiceCredential.valueOf(key)

    /**
     * Stores a value or clears it; a blank value clears. A stored value leaves `MISSING` as `UNKNOWN`,
     * the caller sets any other status.
     */
    fun store(value: String?, expiresAt: Instant?, source: CredentialSource, by: UUID?, now: Instant) {
        val stored = value?.takeIf { it.isNotBlank() }
        this.value = stored
        if (stored == null) {
            status = ServiceCredentialStatus.MISSING
            this.expiresAt = null
            updatedSource = null
            updatedBy = null
        } else {
            if (status == ServiceCredentialStatus.MISSING) status = ServiceCredentialStatus.UNKNOWN
            this.expiresAt = expiresAt
            updatedSource = source
            updatedBy = by
        }
        updatedAt = now
    }

    fun state() = ServiceCredentialState(
        credential = credential,
        present = value != null,
        status = status,
        expiresAt = expiresAt,
        lastUsedAt = lastUsedAt,
        lastRenewedAt = lastRenewedAt,
        lastErrorAt = lastErrorAt,
        lastError = lastError,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        updatedSource = updatedSource,
    )

    override fun toString(): String = "ServiceCredentialEntity($key, redacted)"
}
