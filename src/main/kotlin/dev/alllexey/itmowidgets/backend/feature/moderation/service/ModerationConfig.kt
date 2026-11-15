package dev.alllexey.itmowidgets.backend.feature.moderation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** [revertWindow]: how long after a decision its moderator or an admin may still revert it (docs/contracts/admin.md). */
@ConfigurationProperties("itmowidgets.moderation")
data class ModerationConfig(val revertWindow: Duration = Duration.ofMinutes(10)) {
    init {
        require(revertWindow > Duration.ZERO && revertWindow <= Duration.ofDays(1)) {
            "Moderation revert window must be positive and at most a day"
        }
    }
}
