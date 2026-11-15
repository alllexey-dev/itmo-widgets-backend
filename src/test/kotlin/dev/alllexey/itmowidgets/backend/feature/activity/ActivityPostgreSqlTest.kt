package dev.alllexey.itmowidgets.backend.feature.activity

import dev.alllexey.itmowidgets.backend.feature.activity.persistence.ActiveWeekRepository
import dev.alllexey.itmowidgets.backend.feature.activity.persistence.PeopleDailyRepository
import dev.alllexey.itmowidgets.backend.feature.activity.service.PeopleDailyJob
import dev.alllexey.itmowidgets.backend.feature.activity.service.PeopleStatistics
import dev.alllexey.itmowidgets.backend.feature.activity.service.UserLastSeen
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientReachability
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ScopeTexts
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceDeliveryStore
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.FcmService
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserGroupUpdater
import dev.alllexey.itmowidgets.backend.feature.users.service.UserRegistrationService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounters
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The activity services over real PostgreSQL with the services of the features that own each part of the last activity;
 * only what sends or verifies anything is mocked. One context for every activity suite.
 */
@Import(
    UserLastSeen::class, PeopleStatistics::class, PeopleDailyJob::class, ActiveWeekRepository::class, PeopleDailyRepository::class,
    ClientActivityService::class, DeviceService::class, DeviceDeliveryStore::class, ConnectionService::class, ScopeTexts::class,
    UserService::class, UserRegistrationService::class, UserStatistics::class, UserStatisticsRepository::class,
    DailyCounters::class, ActivityPostgreSqlTest.TimeConfig::class,
)
abstract class ActivityPostgreSqlTest : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock() = MutableClock()
    }

    @MockitoBean private lateinit var fcm: FcmService

    @MockitoBean private lateinit var reachability: ClientReachability

    @MockitoBean private lateinit var jwt: ItmoJwtVerifier

    @MockitoBean private lateinit var groups: UserGroupUpdater

    @Autowired protected lateinit var jdbc: JdbcTemplate

    @Autowired protected lateinit var clock: MutableClock

    /** A request of an app install of [user] at [at], as `client_instances` records it. */
    protected fun clientSeen(user: UUID, at: Instant) {
        jdbc.update(
            """
            INSERT INTO client_instances (id, user_id, kind, instance_id, version, custom, first_seen_at, last_seen_at)
            VALUES (?, ?, 'ANDROID', ?, '2.3.0', false, ?, ?)
            """,
            UUID.randomUUID(),
            user,
            UUID.randomUUID(),
            Timestamp.from(at),
            Timestamp.from(at),
        )
    }

    /** A device registration of [user] at [at]. */
    protected fun deviceLogin(user: UUID, at: Instant) {
        jdbc.update(
            "INSERT INTO devices (id, user_id, fcm_token, device_name, last_login) VALUES (?, ?, ?, 'Synthetic phone', ?)",
            UUID.randomUUID(),
            user,
            "synthetic-fcm-${UUID.randomUUID()}",
            Timestamp.from(at),
        )
    }

    /** A connection of [user] last used at [at], or never used. */
    protected fun connectionUsed(user: UUID, at: Instant?) {
        jdbc.update(
            """
            INSERT INTO connections (id, user_id, client, name, scopes, token_hash, created_at, approved_at, last_used_at)
            VALUES (?, ?, 'AGENT', 'Synthetic agent', 'sport', ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            user,
            UUID.randomUUID().toString().replace("-", "").padEnd(64, 'a'),
            Timestamp.from(EARLY),
            Timestamp.from(EARLY),
            at?.let(Timestamp::from),
        )
    }

    protected fun activeIn(user: UUID, vararg weeks: String) {
        for (week in weeks) jdbc.update("INSERT INTO user_active_weeks (user_id, week_start) VALUES (?, ?)", user, LocalDate.parse(week))
    }

    protected companion object {
        val EARLY: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
