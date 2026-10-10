package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersion
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientBuild
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientVersionWindow
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientVersions
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServiceCredential
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportRun
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStatus
import dev.alllexey.itmowidgets.backend.feature.admin.web.ServiceCredentialRequest
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportUpdateLogRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Admin-only operational views: sport catalog refresh health, the per-platform version metadata, the app builds of
 * active devices and service credentials.
 */
@Service
class AdminSystemService(
    private val access: AdminAccess,
    private val logs: SportUpdateLogRepository,
    private val autoSign: SportAutoSignEntryRepository,
    private val freeSign: SportFreeSignEntryRepository,
    private val versions: AppVersionSettings,
    private val audit: AdminAuditService,
    private val credentialStore: ServiceCredentialStore,
    private val summaries: AdminUserSummaries,
    private val devices: DeviceRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun sport(adminId: UUID): AdminSportStatus {
        access.requireAdmin(adminId)
        val since = clock.instant().minus(WEEK)
        val outcomes = logs.countOutcomesSince(since)
        val errors = logs.countErrorsSince(since).associate { it.category to it.count }
        val runs = outcomes.sumOf { it.count }
        return AdminSportStatus(
            runs = logs.findTop50ByOrderByUpdateTimestampDescIdDesc().map {
                AdminSportRun(
                    it.id, it.updateTimestamp, it.outcome, it.durationMillis, it.receivedLessons, it.newLessonsAdded,
                    it.updatedLessons, it.skippedLessons, it.errorCategory,
                )
            },
            outcomes7d = SportUpdateOutcome.entries.associateWith { outcome -> outcomes.firstOrNull { it.outcome == outcome }?.count ?: 0 },
            errors7d = SportUpdateErrorCategory.entries.associateWith { errors[it] ?: 0 },
            averageDurationMillis7d = if (runs == 0L) null else outcomes.sumOf { it.totalDurationMillis } / runs,
            lastSuccessAt = logs.findLastAt(SportUpdateOutcome.SUCCESS),
            activeAutoSignEntries = autoSign.countActive(),
            activeFreeSignEntries = freeSign.countActive(),
        )
    }

    fun appVersion(adminId: UUID, platform: AppPlatform): AdminAppVersion {
        access.requireAdmin(adminId)
        return versions.view(platform)
    }

    /**
     * Stores all three values of [platform]; the audit lists only the ones that changed, and an unchanged request
     * writes nothing. iOS details start with `IOS: `, Android ones keep their pre-iOS form.
     */
    @Transactional
    fun updateAppVersion(adminId: UUID, platform: AppPlatform, request: AdminAppVersionRequest): AdminAppVersion {
        access.requireAdmin(adminId)
        val next = validated(request)
        val before = versions.current(platform)
        val changes = listOfNotNull(
            "latest ${before.latest} -> ${next.latest}".takeIf { before.latest != next.latest },
            "minimum ${before.minimum} -> ${next.minimum}".takeIf { before.minimum != next.minimum },
            "note changed".takeIf { before.note != next.note },
        )
        if (changes.isNotEmpty()) {
            versions.store(platform, next, adminId, clock.instant())
            val prefix = if (platform == AppPlatform.ANDROID) "" else "${platform.name}: "
            audit.record(adminId, AdminAuditAction.APP_VERSION_CHANGED, "app-version", prefix + changes.joinToString("; "))
        }
        return versions.view(platform)
    }

    @Transactional(readOnly = true)
    fun clientVersions(adminId: UUID): AdminClientVersions {
        access.requireAdmin(adminId)
        val now = clock.instant()
        return AdminClientVersions(clientVersionWindow(now.minus(WEEK)), clientVersionWindow(now.minus(MONTH)))
    }

    private fun clientVersionWindow(since: Instant): AdminClientVersionWindow {
        val (reported, unknown) = devices.countActiveByClientBuild(since).partition { it.platform != null && it.version != null }
        val builds = reported.map {
            AdminClientBuild(
                AppPlatform.valueOf(requireNotNull(it.platform)),
                requireNotNull(it.distribution),
                requireNotNull(it.version),
                requireNotNull(it.build),
                it.devices,
            )
        }.sortedWith(
            compareByDescending<AdminClientBuild> { it.devices }.thenByDescending { it.build }
                .thenBy { it.platform }.thenBy { it.distribution }.thenBy { it.version },
        )
        val unknownDevices = unknown.sumOf { it.devices }
        return AdminClientVersionWindow(builds.sumOf { it.devices } + unknownDevices, unknownDevices, builds)
    }

    fun credentials(adminId: UUID): List<AdminServiceCredential> {
        access.requireAdmin(adminId)
        val states = credentialStore.states()
        val admins = summaries.of(states.mapNotNull { it.updatedBy })
        val now = clock.instant()
        return states.map { state ->
            val window = state.credential.expiresSoonWithin
            val admin = state.updatedBy?.let(admins::get)
            AdminServiceCredential(
                key = state.credential,
                kind = state.credential.kind,
                replaceable = state.credential.replaceable,
                present = state.present,
                status = state.status,
                expiresAt = state.expiresAt,
                expiresSoon = window != null && state.expiresAt != null && state.expiresAt < now.plus(window),
                lastUsedAt = state.lastUsedAt,
                lastRenewedAt = state.lastRenewedAt,
                lastErrorAt = state.lastErrorAt,
                lastError = state.lastError,
                updatedAt = state.updatedAt,
                updatedSource = state.updatedSource,
                updatedByIsu = admin?.isu,
                updatedByName = admin?.name,
            )
        }
    }

    /** Not transactional: the store commits the value with its audit row, then listeners hear of it. */
    fun replaceCredential(adminId: UUID, key: ServiceCredential, request: ServiceCredentialRequest): List<AdminServiceCredential> {
        access.requireAdmin(adminId)
        if (!key.replaceable) throw InvalidRequestDataException("Credential is not replaceable")
        val value = request.value.trim()
        // The message never carries the value.
        if (!CREDENTIAL_VALUE.matches(value)) throw InvalidRequestDataException("Invalid credential value")
        credentialStore.replace(key, value, adminId)
        return credentials(adminId)
    }

    private fun validated(request: AdminAppVersionRequest): AppVersionSettings.AppVersion {
        val latest = request.latest.trim()
        val minimum = request.minimum.trim()
        val note = request.note.trim()
        if (!VERSION.matches(latest) || !VERSION.matches(minimum) || note.length > NOTE_LENGTH || compare(minimum, latest) > 0) {
            throw InvalidRequestDataException("Invalid app version")
        }
        return AppVersionSettings.AppVersion(latest, minimum, note)
    }

    /** Dotted numeric versions compare by number; the leading number run decides for suffixed ones like `2.2-beta`. */
    private fun compare(first: String, second: String): Int {
        fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(first)
        val b = parts(second)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private companion object {
        val WEEK: Duration = Duration.ofDays(7)
        val MONTH: Duration = Duration.ofDays(30)
        val VERSION = Regex("^[0-9]+(\\.[0-9]+){0,3}(-[0-9A-Za-z.]{1,20})?$")
        const val NOTE_LENGTH = 500

        /** Printable ASCII without cookie and header separators. */
        val CREDENTIAL_VALUE = Regex("^[\\x21-\\x7E&&[^;,\"\\\\]]{20,8192}$")
    }
}
