package me.alllexey123.itmowidgets.backend.configs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("itmowidgets.jwt")
data class JwtConfig(
    val secret: String,
    val accessExpirationMs: Long,
    val refreshExpirationMs: Long
)