package dev.alllexey.itmowidgets.backend.configs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("itmowidgets.my-itmo")
data class MyItmoConfig(
    val refreshToken: String? = null,
)