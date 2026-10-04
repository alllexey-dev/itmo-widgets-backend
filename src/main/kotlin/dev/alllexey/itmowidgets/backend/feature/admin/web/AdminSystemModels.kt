package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.credentials.model.CredentialSource
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialKind
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import java.time.Instant

data class AdminSportRun(
    val id: Long,
    val timestamp: Instant,
    val outcome: SportUpdateOutcome,
    val durationMillis: Long,
    val receivedLessons: Int,
    val newLessonsAdded: Int,
    val updatedLessons: Int,
    val skippedLessons: Int,
    val errorCategory: SportUpdateErrorCategory?,
)

/** Catalog refresh health: the last 50 runs, seven-day totals and the live queue sizes. */
data class AdminSportStatus(
    val runs: List<AdminSportRun>,
    /** Every outcome key, zero when absent. */
    val outcomes7d: Map<SportUpdateOutcome, Long>,
    /** Every error category key, zero when absent. */
    val errors7d: Map<SportUpdateErrorCategory, Long>,
    val averageDurationMillis7d: Long?,
    val lastSuccessAt: Instant?,
    val activeAutoSignEntries: Long,
    val activeFreeSignEntries: Long,
)

/** One platform's version metadata served by `/api/app/version-info`; [overridden] is true once stored in settings. */
data class AdminAppVersion(val latest: String, val minimum: String, val note: String, val overridden: Boolean, val updatedAt: Instant?)

data class AdminAppVersionRequest(val latest: String, val minimum: String, val note: String = "")

/** Everything an admin sees about a service credential; the value itself never leaves Backend. */
data class AdminServiceCredential(
    val key: ServiceCredential,
    val kind: ServiceCredentialKind,
    val replaceable: Boolean,
    val present: Boolean,
    val status: ServiceCredentialStatus,
    val expiresAt: Instant?,
    val expiresSoon: Boolean,
    val lastUsedAt: Instant?,
    val lastRenewedAt: Instant?,
    val lastErrorAt: Instant?,
    /** A short technical line such as `EXPIRED login` or `AUTH sport`. */
    val lastError: String?,
    val updatedAt: Instant,
    val updatedSource: CredentialSource?,
    val updatedByIsu: Int?,
    val updatedByName: String?,
)

class ServiceCredentialRequest(val value: String) {
    override fun toString(): String = "ServiceCredentialRequest(redacted)"
}
