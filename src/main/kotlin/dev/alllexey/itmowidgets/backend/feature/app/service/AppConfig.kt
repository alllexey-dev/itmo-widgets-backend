package dev.alllexey.itmowidgets.backend.feature.app.service

import org.springframework.boot.context.properties.ConfigurationProperties

/** Environment fallbacks of the version metadata: Android at the top level, iOS in [ios]. */
@ConfigurationProperties("itmowidgets.app")
data class AppConfig(val version: String, val minVersion: String, val note: String, val ios: Platform) {
    data class Platform(val version: String, val minVersion: String, val note: String)
}
