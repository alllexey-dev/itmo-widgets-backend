package dev.alllexey.itmowidgets.backend.configs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("itmowidgets.reviews-sync")
data class ReviewsSyncConfig(
    val enabled: Boolean = false,
    val baseUrl: URI = URI.create("https://reviews.work.gd"),
    val requestDelay: Duration = Duration.ofMillis(1500),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(baseUrl.scheme in setOf("http", "https") && baseUrl.host != null) { "Reviews sync base URL must be an http(s) URL" }
        require(!requestDelay.isNegative) { "Reviews sync request delay must not be negative" }
        require(connectTimeout > Duration.ZERO) { "Reviews sync connect timeout must be positive" }
        require(requestTimeout > Duration.ZERO) { "Reviews sync request timeout must be positive" }
    }
}
