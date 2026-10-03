package dev.alllexey.itmowidgets.backend.feature.app.service

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("itmowidgets.app")
data class AppConfig(
    val version: String,
    val minVersion: String,
    val note: String,
)
