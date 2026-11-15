package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.links.persistence.LinkStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
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
 * The subject link aggregates on real PostgreSQL with synthetic links and votes in 2032, a year no other test writes
 * to. Moscow is UTC+3: its day starts at 21:00 UTC.
 */
@Import(LinkStatistics::class, LinkStatisticsRepository::class, LinkUsage::class, LinkVoteUsage::class)
class LinkStatisticsTest @Autowired constructor(
    private val statistics: LinkStatistics,
    private val linkUsage: LinkUsage,
    private val voteUsage: LinkVoteUsage,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))
    private var nextIsu = 984_300

    @Test
    fun `an empty period has a zero day for every day and no use`() {
        val empty = StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))

        assertEquals(empty.days.map { DayCount(it, 0) }, statistics.votesPerDay(empty))
        assertEquals(FeatureUsage(0, emptySet()), linkUsage.usage(empty))
        assertEquals(FeatureUsage(0, emptySet()), voteUsage.usage(empty))
    }

    @Test
    fun `links and votes count by the Moscow day they were created`() {
        val (first, second, third) = List(3) { user() }
        val early = link(first, "2032-02-29T20:59:59Z")
        val midnight = link(second, "2032-02-29T21:00:00Z")
        link(second, "2032-03-03T20:59:59Z")
        link(third, "2032-03-03T21:00:00Z")
        vote(early, second, "2032-02-29T21:00:00Z")
        vote(early, third, "2032-03-02T12:00:00Z")
        vote(midnight, third, "2032-03-02T13:00:00Z")
        vote(midnight, first, "2032-03-03T21:00:00Z")

        assertEquals(
            listOf(1L, 2L, 0L),
            statistics.votesPerDay(period).map { it.count },
        )
        assertEquals(FeatureUsage(2, setOf(second)), linkUsage.usage(period))
        assertEquals(FeatureUsage(3, setOf(second, third)), voteUsage.usage(period))
        assertEquals(listOf("LINKS", "LINK_VOTES"), listOf(linkUsage.feature, voteUsage.feature))
    }

    private fun user(): UUID = jdbc.insertUser(nextIsu++, createdAt = Instant.parse("2032-01-01T00:00:00Z"))

    private fun link(owner: UUID, created: String): UUID {
        val id = UUID.randomUUID()
        val url = "https://example.com/$id"
        jdbc.update(
            """
            INSERT INTO subject_links (id, owner_id, subject_id, subject_name, period_key, category, url, normalized_url,
                visibility, created_at, updated_at)
            VALUES (?, ?, 984300, 'Synthetic subject', '2031-2', 'MATERIALS', ?, ?, 'PRIVATE', ?, ?)
            """,
            id,
            owner,
            url,
            url,
            at(created),
            at(created),
        )
        return id
    }

    private fun vote(link: UUID, user: UUID, created: String) {
        jdbc.update("INSERT INTO subject_link_votes (link_id, user_id, value, created_at) VALUES (?, ?, 1, ?)", link, user, at(created))
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))
}
