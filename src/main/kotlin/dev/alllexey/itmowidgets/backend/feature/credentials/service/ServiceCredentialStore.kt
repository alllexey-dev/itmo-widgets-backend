package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmoapi.itmoid.TokenSet
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditAction
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.credentials.model.CredentialSource
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoTokenSnapshot
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialEntity
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialState
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.persistence.ServiceCredentialRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.toJavaInstant

/**
 * The only reader and writer of `service_credentials`. Every call commits on its own, independently of the
 * caller's transaction; no entity or lock survives a call, and no value appears in a log or an exception.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class ServiceCredentialStore(
    private val repository: ServiceCredentialRepository,
    private val audit: AdminAuditService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** Writes a trimmed seed only into a row without a value; a stored value always wins. */
    fun initializeFromBootstrap(credential: ServiceCredential, seed: String?) {
        require(credential.replaceable) { "Credential is not seeded" }
        val value = seed?.trim()
        if (value.isNullOrEmpty()) return
        val row = locked(credential).getValue(credential)
        if (row.value != null) return
        val now = clock.instant()
        row.store(value, seededExpiry(credential, now), CredentialSource.SEED, null, now)
        row.status = ServiceCredentialStatus.UNKNOWN
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun value(credential: ServiceCredential): String? = repository.findById(credential.name).orElse(null)?.value

    /** The three MyITMO rows in one read, so a snapshot never mixes two rotations. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun myItmoSnapshot(): MyItmoTokenSnapshot {
        val rows = repository.findAllById(ServiceCredential.MY_ITMO.map { it.name }).associateBy { it.credential }
        val refresh = rows[ServiceCredential.MY_ITMO_REFRESH_TOKEN]
        val access = rows[ServiceCredential.MY_ITMO_ACCESS_TOKEN]
        return MyItmoTokenSnapshot(
            accessToken = access?.value,
            accessExpiresAt = access?.expiresAt?.toEpochMilli() ?: 0L,
            refreshToken = refresh?.value,
            refreshExpiresAt = refresh?.expiresAt?.toEpochMilli() ?: 0L,
            idToken = rows[ServiceCredential.MY_ITMO_ID_TOKEN]?.value,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun states(): List<ServiceCredentialState> {
        val rows = repository.findAll().associateBy { it.credential }
        return ServiceCredential.entries.map { credential ->
            checkNotNull(rows[credential]) { "Service credential row is missing" }.state()
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun state(credential: ServiceCredential): ServiceCredentialState =
        checkNotNull(repository.findById(credential.name).orElse(null)) { "Service credential row is missing" }.state()

    /** One refresh: all three tokens together. */
    fun rotateMyItmo(tokens: TokenSet) {
        val rows = locked(*ServiceCredential.MY_ITMO.toTypedArray())
        val now = clock.instant()
        rows.getValue(ServiceCredential.MY_ITMO_ACCESS_TOKEN)
            .store(tokens.accessToken, tokens.accessExpiresAt.toJavaInstant(), CredentialSource.ROTATION, null, now)
        rows.getValue(ServiceCredential.MY_ITMO_ID_TOKEN).store(tokens.idToken, null, CredentialSource.ROTATION, null, now)
        val refresh = rows.getValue(ServiceCredential.MY_ITMO_REFRESH_TOKEN)
        refresh.store(tokens.refreshToken, tokens.refreshExpiresAt.toJavaInstant(), CredentialSource.ROTATION, null, now)
        // Tokens straight from the issuer are valid.
        rows.values.forEach { it.status = ServiceCredentialStatus.OK }
        refresh.lastUsedAt = now
        refresh.lastRenewedAt = now
        refresh.lastError = null
    }

    /** The issuing service accepted the credential and possibly rotated it. */
    fun recordRenewal(credential: ServiceCredential, rotatedValue: String?, rotatedExpiresAt: Instant?) {
        val row = locked(credential).getValue(credential)
        val now = clock.instant()
        if (rotatedValue != null) row.store(rotatedValue, rotatedExpiresAt, CredentialSource.ROTATION, null, now)
        if (row.value == null) return
        row.status = ServiceCredentialStatus.OK
        row.lastUsedAt = now
        row.lastRenewedAt = now
        row.lastError = null
    }

    fun recordUse(credential: ServiceCredential) {
        val row = locked(credential).getValue(credential)
        if (row.value == null) return
        row.status = ServiceCredentialStatus.OK
        row.lastUsedAt = clock.instant()
    }

    /** [summary] is a short diagnostic such as `EXPIRED login`, never a value or a payload. */
    fun recordFailure(credential: ServiceCredential, status: ServiceCredentialStatus, summary: String) {
        require(status == ServiceCredentialStatus.EXPIRED || status == ServiceCredentialStatus.FAILED) {
            "Failure status must be EXPIRED or FAILED"
        }
        val row = locked(credential).getValue(credential)
        if (row.value != null) row.status = status
        row.lastErrorAt = clock.instant()
        row.lastError = summary.take(ERROR_LENGTH)
    }

    /** The caller validates and authorizes; the audit row commits with the value. */
    fun replace(credential: ServiceCredential, value: String, adminId: UUID) {
        require(credential.replaceable) { "Credential is not replaceable" }
        val affected = if (credential == ServiceCredential.MY_ITMO_REFRESH_TOKEN) ServiceCredential.MY_ITMO else listOf(credential)
        val rows = locked(*affected.toTypedArray())
        val now = clock.instant()
        val row = rows.getValue(credential)
        row.store(value, seededExpiry(credential, now), CredentialSource.ADMIN, adminId, now)
        row.status = ServiceCredentialStatus.UNKNOWN
        row.lastError = null
        row.lastErrorAt = null
        // Tokens issued for the previous refresh token are refreshed together with the new one.
        affected.filter { it != credential }.forEach { rows.getValue(it).store(null, null, CredentialSource.ADMIN, null, now) }
        audit.record(adminId, AdminAuditAction.SERVICE_CREDENTIAL_REPLACED, "credential:${credential.name}", null)
        events.publishEvent(ServiceCredentialReplaced(credential))
    }

    private fun locked(vararg credentials: ServiceCredential): Map<ServiceCredential, ServiceCredentialEntity> {
        val rows = repository.lockAll(credentials.map { it.name }).associateBy { it.credential }
        check(credentials.all { it in rows }) { "Service credential row is missing" }
        return rows
    }

    /** MyITMO needs an expiry to try a refresh, as the previous bootstrap did; the cookie learns its own. */
    private fun seededExpiry(credential: ServiceCredential, now: Instant): Instant? =
        if (credential == ServiceCredential.MY_ITMO_REFRESH_TOKEN) now.plus(SEED_LIFETIME) else null

    private companion object {
        const val ERROR_LENGTH = 300
        val SEED_LIFETIME: Duration = Duration.ofDays(30)
    }
}
