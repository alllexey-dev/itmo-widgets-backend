package dev.alllexey.itmowidgets.backend.feature.app.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersion
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.app.model.AppSettingEntity
import dev.alllexey.itmowidgets.backend.feature.app.persistence.AppSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** The version metadata per platform. Stored `app_settings` keys win; absent keys fall back to the environment. */
@Service
class AppVersionSettings(private val settings: AppSettingRepository, private val config: AppConfig) {
    data class AppVersion(val latest: String, val minimum: String, val note: String)

    /** The `app_settings` keys of one platform. */
    data class Keys(val latest: String, val minimum: String, val note: String) {
        val all: List<String> get() = listOf(latest, minimum, note)
    }

    fun current(platform: AppPlatform): AppVersion = view(platform).let { AppVersion(it.latest, it.minimum, it.note) }

    @Transactional(readOnly = true)
    fun view(platform: AppPlatform): AdminAppVersion {
        val keys = keys(platform)
        val fallback = fallback(platform)
        val stored = settings.findAllById(keys.all).associateBy { it.key }
        return AdminAppVersion(
            latest = stored[keys.latest]?.value ?: fallback.latest,
            minimum = stored[keys.minimum]?.value ?: fallback.minimum,
            note = stored[keys.note]?.value ?: fallback.note,
            overridden = stored.isNotEmpty(),
            updatedAt = stored.values.maxOfOrNull { it.updatedAt },
        )
    }

    /** Stores all three keys of [platform]; the caller validates, authorizes and audits. */
    @Transactional
    fun store(platform: AppPlatform, version: AppVersion, actorId: UUID, at: Instant) {
        val keys = keys(platform)
        settings.saveAll(
            listOf(keys.latest to version.latest, keys.minimum to version.minimum, keys.note to version.note)
                .map { (key, value) -> AppSettingEntity(key, value, at, actorId) },
        )
    }

    private fun fallback(platform: AppPlatform): AppVersion = when (platform) {
        AppPlatform.ANDROID -> AppVersion(config.version, config.minVersion, config.note)
        AppPlatform.IOS -> AppVersion(config.ios.version, config.ios.minVersion, config.ios.note)
    }

    companion object {
        val ANDROID_KEYS = Keys(latest = "app.latest", minimum = "app.minimum", note = "app.note")
        val IOS_KEYS = Keys(latest = "app.ios.latest", minimum = "app.ios.minimum", note = "app.ios.note")

        fun keys(platform: AppPlatform): Keys = when (platform) {
            AppPlatform.ANDROID -> ANDROID_KEYS
            AppPlatform.IOS -> IOS_KEYS
        }
    }
}
