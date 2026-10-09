package dev.alllexey.itmowidgets.backend.feature.push.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** [refresh]: how long an unchanged reported build goes unwritten; it is also the precision of the "seen" time. */
@ConfigurationProperties("itmowidgets.client-version")
data class ClientVersionConfig(val refresh: Duration = Duration.ofHours(1)) {
    init {
        require(!refresh.isNegative && !refresh.isZero) { "Client version refresh must be positive" }
    }
}
