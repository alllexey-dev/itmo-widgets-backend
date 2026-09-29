package dev.alllexey.itmowidgets.backend.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A secret of an external service, one `service_credentials` row each; the name is the row key. */
enum class ServiceCredential(
    val kind: ServiceCredentialKind,
    /** Whether an admin may replace the value; the others are issued by a refresh. */
    val replaceable: Boolean,
    /** How long before `expires_at` the credential counts as expiring soon; `null` never does. */
    val expiresSoonWithin: Duration?,
) {
    MY_ITMO_REFRESH_TOKEN(ServiceCredentialKind.REFRESH_TOKEN, true, Duration.ofDays(1)),
    MY_ITMO_ACCESS_TOKEN(ServiceCredentialKind.ACCESS_TOKEN, false, null),
    MY_ITMO_ID_TOKEN(ServiceCredentialKind.ID_TOKEN, false, null),
    ISU_KEYCLOAK_IDENTITY(ServiceCredentialKind.COOKIE, true, Duration.ofDays(14)),
    GEMINI_API_KEY(ServiceCredentialKind.API_KEY, true, null);

    companion object {
        val MY_ITMO = listOf(MY_ITMO_REFRESH_TOKEN, MY_ITMO_ACCESS_TOKEN, MY_ITMO_ID_TOKEN)
    }
}

enum class ServiceCredentialKind { REFRESH_TOKEN, ACCESS_TOKEN, ID_TOKEN, COOKIE, API_KEY }

/** `MISSING` has no value; `UNKNOWN` is copied, seeded or replaced and not yet used. */
enum class ServiceCredentialStatus { MISSING, UNKNOWN, OK, EXPIRED, FAILED }

enum class CredentialSource { MIGRATION, SEED, ROTATION, ADMIN }

/** Everything known about a credential except its value. */
data class ServiceCredentialState(
    val credential: ServiceCredential,
    val present: Boolean,
    val status: ServiceCredentialStatus,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val lastRenewedAt: Instant?,
    val lastErrorAt: Instant?,
    val lastError: String?,
    val updatedAt: Instant,
    val updatedBy: UUID?,
    val updatedSource: CredentialSource?,
)
