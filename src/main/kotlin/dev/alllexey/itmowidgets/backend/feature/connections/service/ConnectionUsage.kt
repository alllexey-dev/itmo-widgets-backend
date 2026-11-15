package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

/**
 * `CONNECTIONS`, the connection row of the feature-use statistics: connections of every client created in the period
 * and their users.
 */
@Component
class ConnectionUsage(private val repository: ConnectionStatisticsRepository) : UsageSource {
    override val feature = "CONNECTIONS"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.connectionUsage(period)
}
