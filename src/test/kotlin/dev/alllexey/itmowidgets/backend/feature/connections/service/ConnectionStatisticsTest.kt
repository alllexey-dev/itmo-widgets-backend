package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType.AGENT
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType.APP
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType.MCP
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType.WEB
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStatsDay
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingFunnel
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingStep
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterKey
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterStore
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The connection aggregates on real PostgreSQL with synthetic connections in 2032, a year no other test writes to.
 * Moscow is UTC+3: its day starts at 21:00 UTC.
 */
@Import(
    ConnectionStatistics::class,
    ConnectionStatisticsRepository::class,
    ConnectionUsage::class,
    DailyCounterReader::class,
    DailyCounterStore::class,
)
class ConnectionStatisticsTest @Autowired constructor(
    private val statistics: ConnectionStatistics,
    private val counters: DailyCounterStore,
    private val connectionUsage: ConnectionUsage,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-02"))
    private var nextIsu = 984_500

    @Test
    fun `an empty period has a zero day for every day with every client and no use`() {
        val empty = StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))

        assertEquals(empty.days.map { ConnectionStatsDay(it, counts(), counts()) }, statistics.stats(empty).days)
        assertEquals(FeatureUsage(0, emptySet()), connectionUsage.usage(empty))
    }

    @Test
    fun `connections count per client by the Moscow day they were created and revoked`() {
        val (first, second, third) = List(3) { user() }
        connection(first, WEB, "2032-03-01T10:00:00Z")
        connection(first, AGENT, "2032-02-29T21:00:00Z", revoked = "2032-03-02T10:00:00Z")
        connection(second, MCP, "2032-02-20T10:00:00Z", revoked = "2032-03-01T20:59:59Z")
        connection(third, WEB, "2032-03-02T21:00:00Z")

        assertEquals(
            listOf(
                ConnectionStatsDay(LocalDate.parse("2032-03-01"), counts(WEB to 1, AGENT to 1), counts(MCP to 1)),
                ConnectionStatsDay(LocalDate.parse("2032-03-02"), counts(), counts(AGENT to 1)),
            ),
            statistics.stats(period).days,
        )
        assertEquals(FeatureUsage(2, setOf(first)), connectionUsage.usage(period))
        assertEquals("CONNECTIONS", connectionUsage.feature)
    }

    @Test
    fun `the pairing funnel sums the step counters per client over the period, every client and step present`() {
        counters.add(
            mapOf(
                step("2032-02-29", PairingStep.CREATED, WEB) to 100L,
                step("2032-03-01", PairingStep.CREATED, WEB) to 5L,
                step("2032-03-02", PairingStep.CREATED, WEB) to 2L,
                step("2032-03-02", PairingStep.OPENED, WEB) to 6L,
                step("2032-03-02", PairingStep.CLAIMED, WEB) to 4L,
                step("2032-03-01", PairingStep.EXPIRED, AGENT) to 1L,
                step("2032-03-03", PairingStep.CLAIMED, AGENT) to 100L,
                // A client this build does not know is left out.
                DailyCounterKey(LocalDate.parse("2032-03-01"), PairingStep.METRIC, PairingStep.CREATED.name, "DESKTOP") to 9L,
            ),
        )

        assertEquals(
            listOf(
                PairingFunnel(AGENT, steps(PairingStep.EXPIRED to 1)),
                PairingFunnel(MCP, steps()),
                PairingFunnel(WEB, steps(PairingStep.CREATED to 7, PairingStep.OPENED to 6, PairingStep.CLAIMED to 4)),
                PairingFunnel(APP, steps()),
            ),
            statistics.pairing(period),
        )
        // Before any counter: every client with zeros.
        assertEquals(
            ClientType.entries.map { PairingFunnel(it, steps()) },
            statistics.pairing(StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))),
        )
    }

    private fun step(day: String, step: PairingStep, client: ClientType) =
        DailyCounterKey(LocalDate.parse(day), PairingStep.METRIC, step.name, client.name)

    private fun steps(vararg given: Pair<PairingStep, Int>): Map<PairingStep, Long> {
        val map = given.toMap()
        return PairingStep.entries.associateWith { (map[it] ?: 0).toLong() }
    }

    private fun user(): UUID = jdbc.insertUser(nextIsu++, createdAt = Instant.parse("2032-01-01T00:00:00Z"))

    private fun connection(user: UUID, client: ClientType, created: String, revoked: String? = null) {
        jdbc.update(
            """
            INSERT INTO connections (id, user_id, client, name, scopes, created_at, approved_at, revoked_at)
            VALUES (?, ?, ?, 'Synthetic client', 'user', ?, ?, ?)
            """,
            UUID.randomUUID(),
            user,
            client.name,
            at(created),
            at(created),
            revoked?.let(::at),
        )
    }

    /** Every client, 0 unless given. */
    private fun counts(vararg given: Pair<ClientType, Int>): Map<ClientType, Long> {
        val map = given.toMap()
        return ClientType.entries.associateWith { (map[it] ?: 0).toLong() }
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))
}
