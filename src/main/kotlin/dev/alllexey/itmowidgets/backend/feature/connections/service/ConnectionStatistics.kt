package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStats
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStatsDay
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The connection figures of a statistics period: one aggregate query in a read-only transaction, no rows loaded. */
@Service
class ConnectionStatistics(private val repository: ConnectionStatisticsRepository) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): ConnectionStats {
        val rows = repository.perDay(period)
        val created = ClientType.entries.associateWith { client ->
            period.zeroFilled(rows.filter { it.client == client }.map { DayCount(it.day, it.created) })
        }
        val revoked = ClientType.entries.associateWith { client ->
            period.zeroFilled(rows.filter { it.client == client }.map { DayCount(it.day, it.revoked) })
        }
        val days = period.days.mapIndexed { index, day ->
            ConnectionStatsDay(
                day = day,
                created = ClientType.entries.associateWith { created.getValue(it)[index].count },
                revoked = ClientType.entries.associateWith { revoked.getValue(it)[index].count },
            )
        }
        return ConnectionStats(days)
    }
}
