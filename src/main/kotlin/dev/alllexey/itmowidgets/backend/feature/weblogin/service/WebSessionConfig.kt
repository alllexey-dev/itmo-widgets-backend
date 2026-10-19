package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long a browser session lives: [idleTimeout] without requests or refreshes, [maxLifetime] after sign-in at most.
 * Its access token lives [accessTtl]; the refresh token then rotates both. Admin and moderation routes accept the
 * session only within [adminMaxAge] after sign-in; a refresh does not renew that.
 */
@ConfigurationProperties("itmowidgets.web-session")
data class WebSessionConfig(
    val idleTimeout: Duration = Duration.ofDays(30),
    val maxLifetime: Duration = Duration.ofDays(180),
    val adminMaxAge: Duration = Duration.ofHours(12),
    val accessTtl: Duration = Duration.ofHours(1),
) {
    init {
        require(idleTimeout > Duration.ZERO) { "Web session idle timeout must be positive" }
        require(maxLifetime > Duration.ZERO) { "Web session max lifetime must be positive" }
        require(adminMaxAge > Duration.ZERO) { "Web session admin max age must be positive" }
        require(adminMaxAge <= maxLifetime) { "Web session admin max age must not exceed its max lifetime" }
        require(accessTtl > Duration.ZERO) { "Web session access TTL must be positive" }
        require(accessTtl <= idleTimeout) { "Web session access TTL must not exceed its idle timeout" }
    }
}
