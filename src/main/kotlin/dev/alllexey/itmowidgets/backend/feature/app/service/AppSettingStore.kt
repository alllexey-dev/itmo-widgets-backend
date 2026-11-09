package dev.alllexey.itmowidgets.backend.feature.app.service

import dev.alllexey.itmowidgets.backend.feature.app.model.AppSettingEntity
import dev.alllexey.itmowidgets.backend.feature.app.persistence.AppSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Single `app_settings` rows for other features. The feature that owns a key parses, validates, authorizes and
 * audits it; this store only reads and writes the row.
 */
@Service
class AppSettingStore(private val settings: AppSettingRepository) {
    @Transactional(readOnly = true)
    fun find(key: String): AppSettingEntity? = settings.findById(key).orElse(null)

    @Transactional
    fun store(key: String, value: String, actorId: UUID, at: Instant) {
        settings.save(AppSettingEntity(key, value, at, actorId))
    }
}
