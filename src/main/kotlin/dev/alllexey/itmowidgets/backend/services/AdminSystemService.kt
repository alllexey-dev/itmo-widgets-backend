package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminAppVersion
import dev.alllexey.itmowidgets.backend.dto.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.dto.AdminSportRun
import dev.alllexey.itmowidgets.backend.dto.AdminSportStatus
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportUpdateLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Admin-only operational views: sport catalog refresh health and the Android version metadata. */
@Service
class AdminSystemService(
    private val access: AdminAccess,
    private val logs: SportUpdateLogRepository,
    private val autoSign: SportAutoSignEntryRepository,
    private val freeSign: SportFreeSignEntryRepository,
    private val versions: AppVersionSettings,
    private val audit: AdminAuditService,
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
                AdminSportRun(it.id, it.updateTimestamp, it.outcome, it.durationMillis, it.receivedLessons, it.newLessonsAdded,
                    it.updatedLessons, it.skippedLessons, it.errorCategory)
            },
            outcomes7d = SportUpdateOutcome.entries.associateWith { outcome -> outcomes.firstOrNull { it.outcome == outcome }?.count ?: 0 },
            errors7d = SportUpdateErrorCategory.entries.associateWith { errors[it] ?: 0 },
            averageDurationMillis7d = if (runs == 0L) null else outcomes.sumOf { it.totalDurationMillis } / runs,
            lastSuccessAt = logs.findLastAt(SportUpdateOutcome.SUCCESS),
            activeAutoSignEntries = autoSign.countActive(),
            activeFreeSignEntries = freeSign.countActive(),
        )
    }

    fun appVersion(adminId: UUID): AdminAppVersion {
        access.requireAdmin(adminId)
        return versions.view()
    }

    /** Stores all three values; the audit lists only the ones that changed, and an unchanged request writes nothing. */
    @Transactional
    fun updateAppVersion(adminId: UUID, request: AdminAppVersionRequest): AdminAppVersion {
        access.requireAdmin(adminId)
        val next = validated(request)
        val before = versions.current()
        val changes = listOfNotNull(
            "latest ${before.latest} -> ${next.latest}".takeIf { before.latest != next.latest },
            "minimum ${before.minimum} -> ${next.minimum}".takeIf { before.minimum != next.minimum },
            "note changed".takeIf { before.note != next.note },
        )
        if (changes.isNotEmpty()) {
            versions.store(next, adminId, clock.instant())
            audit.record(adminId, AdminAuditAction.APP_VERSION_CHANGED, "app-version", changes.joinToString("; "))
        }
        return versions.view()
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
        val VERSION = Regex("^[0-9]+(\\.[0-9]+){0,3}(-[0-9A-Za-z.]{1,20})?$")
        const val NOTE_LENGTH = 500
    }
}
