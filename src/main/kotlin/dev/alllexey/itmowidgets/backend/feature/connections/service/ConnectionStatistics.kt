package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStats
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStatsDay
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingFunnel
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingStep
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The connection figures of a statistics period, each in a read-only transaction: the connections from one aggregate
 * query, no rows loaded, and the pairing funnel from the [PairingStep] counters (docs/contracts/server-statistics.md).
 */
@Service
class ConnectionStatistics(private val repository: ConnectionStatisticsRepository, private val counters: DailyCounterReader) {
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

    /** One funnel per [ClientType], in their order; a client or a step without a count is 0. */
    @Transactional(readOnly = true)
    fun pairing(period: StatsPeriod): List<PairingFunnel> {
        val byStep = counters.byDim1AndDim2(PairingStep.METRIC, period)
        return ClientType.entries.map { client ->
            PairingFunnel(client, PairingStep.entries.associateWith { byStep[it.name]?.get(client.name) ?: 0 })
        }
    }
}
