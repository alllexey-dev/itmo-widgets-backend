package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureUsage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPairingFunnel
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPrivacyStats
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStats
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionStatsDay
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingFunnel
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingStep
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionStatistics
import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipStatsDay
import dev.alllexey.itmowidgets.backend.feature.social.model.SocialStats
import dev.alllexey.itmowidgets.backend.feature.social.service.SocialStatistics
import dev.alllexey.itmowidgets.backend.feature.users.model.PrivacyCounts
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserStats
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How the features tab composes the usage sources and the statistics services; the figures themselves are tested per feature. */
class AdminFeatureStatsServiceTest {
    private val admin = UUID.randomUUID()
    private val roles = mock(UserRoleRepository::class.java).also {
        `when`(it.existsByUserIdAndRole(admin, UserRole.ADMIN)).thenReturn(true)
    }
    private val social = mock(SocialStatistics::class.java)
    private val connections = mock(ConnectionStatistics::class.java)
    private val users = mock(UserStatistics::class.java)
    private val clock = Clock.fixed(Instant.parse("2032-03-02T09:00:00Z"), ZoneOffset.UTC)
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-02"))
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val third = UUID.randomUUID()
    private val fourth = UUID.randomUUID()

    @Test
    fun `rows come by feature name and only rows of the users' own actions make social users`() {
        val sources = listOf(
            Source("SPORT_AUTO_SIGN", FeatureUsage(3, setOf(first, second))),
            Source("SCHEDULE", FeatureUsage(null, setOf(first, fourth)), ownAction = false),
            Source("FRIENDS", FeatureUsage(1, setOf(second, third))),
        )
        `when`(social.stats(period)).thenReturn(
            SocialStats(
                usersWithFriends = 7,
                days = period.days.mapIndexed { index, day -> FriendshipStatsDay(day, index + 1L, index.toLong()) },
            ),
        )
        `when`(connections.stats(period)).thenReturn(
            ConnectionStats(period.days.map { ConnectionStatsDay(it, clients(1), clients(0)) }),
        )
        val privacy = PrivacyCounts(visibility(1), visibility(2), visibility(3))
        `when`(users.stats()).thenReturn(UserStats(accounts = 40, privacy = privacy))
        `when`(users.accountsAmong(setOf(first, second, third, fourth))).thenReturn(setOf(first, second, third, fourth))
        `when`(connections.pairing(period)).thenReturn(
            ClientType.entries.map { client ->
                PairingFunnel(client, PairingStep.entries.associateWith { it.ordinal + client.ordinal * 10L })
            },
        )

        val stats = service(sources).features(admin, period.from, period.to)

        assertEquals(
            listOf(
                AdminFeatureUsage("FRIENDS", 1, 2),
                AdminFeatureUsage("SCHEDULE", null, 2),
                AdminFeatureUsage("SPORT_AUTO_SIGN", 3, 2),
            ),
            stats.features,
        )
        // The fourth user only has a schedule.
        assertEquals(3, stats.socialUsers)
        assertEquals(40L to 7L, stats.users to stats.usersWithFriends)
        assertEquals(
            listOf(
                AdminFeatureStatsDay(period.from, 1, 0, clients(1), clients(0)),
                AdminFeatureStatsDay(period.to, 2, 1, clients(1), clients(0)),
            ),
            stats.days,
        )
        assertEquals(AdminPrivacyStats(visibility(1), visibility(2), visibility(3)), stats.privacy)
        assertEquals(
            listOf(
                AdminPairingFunnel(ClientType.AGENT, created = 0, opened = 1, approved = 2, claimed = 3, expired = 4),
                AdminPairingFunnel(ClientType.MCP, created = 10, opened = 11, approved = 12, claimed = 13, expired = 14),
                AdminPairingFunnel(ClientType.WEB, created = 20, opened = 21, approved = 22, claimed = 23, expired = 24),
            ),
            stats.pairing,
        )
    }

    @Test
    fun `ids that are no account, such as a deleted account's placeholder, use no feature`() {
        val placeholder = UUID.randomUUID()
        val sources = listOf(
            Source("LINKS", FeatureUsage(2, setOf(first, placeholder))),
            Source("REPORTS", FeatureUsage(1, setOf(placeholder))),
            Source("SCHEDULE", FeatureUsage(null, setOf(second)), ownAction = false),
        )
        `when`(users.accountsAmong(setOf(first, second, placeholder))).thenReturn(setOf(first, second))
        `when`(social.stats(period)).thenReturn(SocialStats(0, period.days.map { FriendshipStatsDay(it, 0, 0) }))
        `when`(connections.stats(period)).thenReturn(ConnectionStats(period.days.map { ConnectionStatsDay(it, clients(0), clients(0)) }))
        `when`(users.stats()).thenReturn(UserStats(accounts = 2, privacy = PrivacyCounts(visibility(0), visibility(0), visibility(0))))
        val noSteps = PairingStep.entries.associateWith { 0L }
        `when`(connections.pairing(period)).thenReturn(ClientType.entries.map { PairingFunnel(it, noSteps) })

        val stats = service(sources).features(admin, period.from, period.to)

        // What the placeholder keeps still happened: it stays in the events.
        assertEquals(
            listOf(AdminFeatureUsage("LINKS", 2, 1), AdminFeatureUsage("REPORTS", 1, 0), AdminFeatureUsage("SCHEDULE", null, 1)),
            stats.features,
        )
        assertEquals(1, stats.socialUsers)
    }

    @Test
    fun `a non-admin is refused before any source is asked`() {
        val source = mock(UsageSource::class.java)
        `when`(source.feature).thenReturn("FRIENDS")
        val service = service(listOf(source))

        assertFailsWith<PermissionDeniedException> { service.features(UUID.randomUUID(), null, null) }
        verifyNoInteractions(social, connections, users)
    }

    @Test
    fun `two sources of one feature are a wiring error`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            service(listOf(Source("LINKS", FeatureUsage(0, emptySet())), Source("LINKS", FeatureUsage(0, emptySet()))))
        }
        assertEquals("Usage sources repeat the features [LINKS]", failure.message)
    }

    private fun service(sources: List<UsageSource>) =
        AdminFeatureStatsService(AdminAccess(roles), sources, social, connections, users, clock)

    private fun clients(count: Long): Map<ClientType, Long> = ClientType.entries.associateWith { count }

    private fun visibility(count: Long): Map<SharingVisibility, Long> = SharingVisibility.entries.associateWith { count }

    private class Source(override val feature: String, private val used: FeatureUsage, override val ownAction: Boolean = true) :
        UsageSource {
        override fun usage(period: StatsPeriod) = used
    }
}
