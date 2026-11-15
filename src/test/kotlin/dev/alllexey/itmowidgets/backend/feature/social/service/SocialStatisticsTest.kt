package dev.alllexey.itmowidgets.backend.feature.social.service

import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipStatsDay
import dev.alllexey.itmowidgets.backend.feature.social.persistence.SocialStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
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
 * The friendship aggregates on real PostgreSQL with synthetic friendships in 2032, a year no other test writes to;
 * `usersWithFriends` is the state now and is compared as a delta. Moscow is UTC+3: its day starts at 21:00 UTC.
 */
@Import(SocialStatistics::class, SocialStatisticsRepository::class, FriendUsage::class)
class SocialStatisticsTest @Autowired constructor(
    private val statistics: SocialStatistics,
    private val friendUsage: FriendUsage,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))
    private var nextIsu = 984_400

    @Test
    fun `an empty period has a zero day for every day and no use`() {
        val empty = StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))

        assertEquals(empty.days.map { FriendshipStatsDay(it, 0, 0) }, statistics.stats(empty).days)
        assertEquals(FeatureUsage(0, emptySet()), friendUsage.usage(empty))
    }

    @Test
    fun `requests count by their Moscow sending day, friendships by their acceptance, both sides use the feature`() {
        val before = statistics.stats(period).usersWithFriends
        val (first, second, third, fourth, fifth) = List(5) { user() }
        friendship(first, second, "2032-03-01T10:00:00Z")
        friendship(third, first, "2032-02-25T10:00:00Z", accepted = "2032-03-02T10:00:00Z")
        friendship(second, third, "2032-03-02T21:00:00Z", accepted = "2032-03-02T21:30:00Z")
        friendship(fourth, fifth, "2032-02-20T10:00:00Z", accepted = "2032-02-21T10:00:00Z")
        friendship(fifth, first, "2032-03-03T21:00:00Z")

        val stats = statistics.stats(period)

        assertEquals(
            listOf(
                FriendshipStatsDay(LocalDate.parse("2032-03-01"), requests = 1, accepts = 0),
                FriendshipStatsDay(LocalDate.parse("2032-03-02"), requests = 0, accepts = 1),
                FriendshipStatsDay(LocalDate.parse("2032-03-03"), requests = 1, accepts = 1),
            ),
            stats.days,
        )
        // A pending request makes nobody a user with friends.
        assertEquals(5, stats.usersWithFriends - before)
        // Two requests sent; the addressee of one accepted in the period uses the feature as well.
        assertEquals(FeatureUsage(2, setOf(first, second, third)), friendUsage.usage(period))
        assertEquals("FRIENDS", friendUsage.feature)
    }

    private fun user(): UUID = jdbc.insertUser(nextIsu++, createdAt = Instant.parse("2032-01-01T00:00:00Z"))

    private fun friendship(requester: UUID, addressee: UUID, created: String, accepted: String? = null) {
        jdbc.update(
            "INSERT INTO friendships (id, requester_id, addressee_id, status, created_at, responded_at) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            requester,
            addressee,
            if (accepted == null) "PENDING" else "ACCEPTED",
            at(created),
            accepted?.let(::at),
        )
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))
}
