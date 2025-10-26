package dev.alllexey.itmowidgets.backend.configs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("itmowidgets.app")
data class AppConfig(
    val version: String
)