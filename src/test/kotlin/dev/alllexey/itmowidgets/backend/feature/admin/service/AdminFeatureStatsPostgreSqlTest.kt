package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureUsage
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStats
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStatsDay
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionStatistics
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationStatisticsRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ReportUsage
import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipStatsDay
import dev.alllexey.itmowidgets.backend.feature.social.model.SocialStats
import dev.alllexey.itmowidgets.backend.feature.social.service.SocialStatistics
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The features tab on real PostgreSQL after an account deletion: what the deleted account did in the period stays as
 * the placeholder's, and the placeholder is no user of any row. Synthetic rows in 2032, a year no other test reports in.
 */
@Import(ReportUsage::class, ModerationStatisticsRepository::class, UserStatistics::class, UserStatisticsRepository::class)
class AdminFeatureStatsPostgreSqlTest @Autowired constructor(
    private val reports: ReportUsage,
    private val users: UserStatistics,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-04-01"), LocalDate.parse("2032-04-03"))
    private val clock = Clock.fixed(Instant.parse("2032-04-03T09:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `a deleted account's reports in the period stay in the events, its placeholder in no user count`() {
        val admin = UUID.randomUUID()
        val stays = jdbc.insertUser(985_001)
        val deleted = jdbc.insertUser(985_002)
        report(stays, "2032-04-01T10:00:00Z")
        report(deleted, "2032-04-02T10:00:00Z")
        report(deleted, "2032-04-03T10:00:00Z")
        AccountDeletionService(jdbc, clock).delete(985_002)
        val placeholder = jdbc.queryForObject(
            "SELECT reporter_id FROM moderation_reports WHERE created_at = ?",
            UUID::class.java,
            at("2032-04-02T10:00:00Z"),
        )
        // The source reports the placeholder as it finds it; the tab leaves it out.
        assertEquals(setOf(stays, placeholder), reports.usage(period).users)

        val stats = service(admin).features(admin, period.from, period.to)

        assertEquals(listOf(AdminFeatureUsage("REPORTS", 3, 1)), stats.features)
        assertEquals(1, stats.socialUsers)
    }

    private fun service(admin: UUID): AdminFeatureStatsService {
        val roles = mock(UserRoleRepository::class.java)
        `when`(roles.existsByUserIdAndRole(admin, UserRole.ADMIN)).thenReturn(true)
        val social = mock(SocialStatistics::class.java)
        `when`(social.stats(period)).thenReturn(SocialStats(0, period.days.map { FriendshipStatsDay(it, 0, 0) }))
        val connections = mock(ConnectionStatistics::class.java)
        val none = ClientType.entries.associateWith { 0L }
        `when`(connections.stats(period)).thenReturn(ConnectionStats(period.days.map { ConnectionStatsDay(it, none, none) }))
        return AdminFeatureStatsService(AdminAccess(roles), listOf(reports), social, connections, users, clock)
    }

    private fun report(reporter: UUID, created: String) {
        jdbc.update(
            "INSERT INTO moderation_reports (id, target_type, target_id, reporter_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            ModerationTargetType.SUBJECT_RESOURCE.name,
            UUID.randomUUID(),
            reporter,
            ReportReason.SPAM.name,
            at(created),
        )
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))
}
