package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.configs.AppConfig
import dev.alllexey.itmowidgets.backend.dto.AdminAppVersion
import dev.alllexey.itmowidgets.backend.model.AppSettingEntity
import dev.alllexey.itmowidgets.backend.repositories.AppSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** The Android version metadata. Stored `app_settings` keys win; absent keys fall back to the environment. */
@Service
class AppVersionSettings(private val settings: AppSettingRepository, private val config: AppConfig) {
    data class AppVersion(val latest: String, val minimum: String, val note: String)

    fun current(): AppVersion = view().let { AppVersion(it.latest, it.minimum, it.note) }

    @Transactional(readOnly = true)
    fun view(): AdminAppVersion {
        val stored = settings.findAllById(KEYS).associateBy { it.key }
        return AdminAppVersion(
            latest = stored[LATEST]?.value ?: config.version,
            minimum = stored[MINIMUM]?.value ?: config.minVersion,
            note = stored[NOTE]?.value ?: config.note,
            overridden = stored.isNotEmpty(),
            updatedAt = stored.values.maxOfOrNull { it.updatedAt },
        )
    }

    /** Stores all three keys; the caller validates, authorizes and audits. */
    @Transactional
    fun store(version: AppVersion, actorId: UUID, at: Instant) {
        settings.saveAll(listOf(LATEST to version.latest, MINIMUM to version.minimum, NOTE to version.note)
            .map { (key, value) -> AppSettingEntity(key, value, at, actorId) })
    }

    companion object {
        const val LATEST = "app.latest"
        const val MINIMUM = "app.minimum"
        const val NOTE = "app.note"
        val KEYS = listOf(LATEST, MINIMUM, NOTE)
    }
}
