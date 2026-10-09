package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** How long a browser session lives: [idleTimeout] without requests, [maxLifetime] after sign-in at most. */
@ConfigurationProperties("itmowidgets.web-session")
data class WebSessionConfig(val idleTimeout: Duration = Duration.ofDays(14), val maxLifetime: Duration = Duration.ofDays(60)) {
    init {
        require(idleTimeout > Duration.ZERO) { "Web session idle timeout must be positive" }
        require(maxLifetime > Duration.ZERO) { "Web session max lifetime must be positive" }
        require(maxLifetime < WebSessionService.RETENTION) { "Web session max lifetime must be shorter than its retention" }
    }
}
