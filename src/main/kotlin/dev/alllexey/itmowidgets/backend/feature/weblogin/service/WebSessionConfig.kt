package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long a browser session lives: [idleTimeout] without requests, [maxLifetime] after sign-in at most.
 * Admin and moderation routes accept it only within [adminMaxAge] after sign-in.
 */
@ConfigurationProperties("itmowidgets.web-session")
data class WebSessionConfig(
    val idleTimeout: Duration = Duration.ofDays(14),
    val maxLifetime: Duration = Duration.ofDays(60),
    val adminMaxAge: Duration = Duration.ofHours(12),
) {
    init {
        require(idleTimeout > Duration.ZERO) { "Web session idle timeout must be positive" }
        require(maxLifetime > Duration.ZERO) { "Web session max lifetime must be positive" }
        require(maxLifetime < WebSessionService.RETENTION) { "Web session max lifetime must be shorter than its retention" }
        require(adminMaxAge > Duration.ZERO) { "Web session admin max age must be positive" }
        require(adminMaxAge <= maxLifetime) { "Web session admin max age must not exceed its max lifetime" }
    }
}
