package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPushStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRouteStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServerDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServerStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUpstreamStats
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoOperationStats
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoStats
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoStatistics
import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome
import dev.alllexey.itmowidgets.backend.feature.push.model.PushStats
import dev.alllexey.itmowidgets.backend.feature.push.model.PushTypeStats
import dev.alllexey.itmowidgets.backend.feature.push.service.PushStatistics
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.metrics.DayLatency
import dev.alllexey.itmowidgets.backend.platform.metrics.HttpStatistics
import dev.alllexey.itmowidgets.backend.platform.metrics.HttpStats
import dev.alllexey.itmowidgets.backend.platform.metrics.RouteStats
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
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

/** How the server tab zero-fills and composes the counter statistics; the sums themselves are tested per owner. */
class AdminServerStatsServiceTest {
    private val admin = UUID.randomUUID()
    private val roles = mock(UserRoleRepository::class.java).also {
        `when`(it.existsByUserIdAndRole(admin, UserRole.ADMIN)).thenReturn(true)
    }
    private val counters = mock(DailyCounterReader::class.java)
    private val http = mock(HttpStatistics::class.java)
    private val push = mock(PushStatistics::class.java)
    private val myItmo = mock(MyItmoStatistics::class.java)
    private val clock = Clock.fixed(Instant.parse("2032-03-03T09:00:00Z"), ZoneOffset.UTC)
    private val service = AdminServerStatsService(AdminAccess(roles), counters, http, push, myItmo, clock)
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))
    private val first = LocalDate.parse("2032-03-01")
    private val second = LocalDate.parse("2032-03-02")
    private val third = LocalDate.parse("2032-03-03")

    @Test
    fun `every day of the period gets a row, a day without counters zeros and no times`() {
        // Collection started on the second day.
        `when`(counters.firstDay()).thenReturn(second)
        `when`(http.stats(period)).thenReturn(
            HttpStats(
                requests = listOf(DayCount(second, 10), DayCount(third, 4)),
                errors5xx = listOf(DayCount(third, 1)),
                latency = listOf(DayLatency(second, 20, 90)),
                routes = listOf(RouteStats("sport", 14, 2, 1, 20, 90)),
            ),
        )
        `when`(myItmo.stats(period)).thenReturn(
            MyItmoStats(
                calls = listOf(DayCount(second, 6)),
                failures = listOf(DayCount(second, 1)),
                operations = listOf(MyItmoOperationStats("sportSchedule", 6, failures(MyItmoFailureKind.NETWORK to 1))),
            ),
        )
        `when`(push.stats(period)).thenReturn(
            PushStats(
                sent = listOf(DayCount(third, 3)),
                failed = listOf(DayCount(second, 1)),
                tokenGone = listOf(DayCount(third, 2)),
                types = listOf(
                    PushTypeStats("FRIEND_REQUEST", PushOutcome.entries.associateWith { it.ordinal + 1L }),
                ),
            ),
        )

        assertEquals(
            AdminServerStats(
                from = first,
                to = third,
                collectedSince = second,
                days = listOf(
                    AdminServerDay(first, 0, 0, null, null, 0, 0, 0, 0, 0),
                    AdminServerDay(second, 10, 0, 20, 90, 6, 1, 0, 1, 0),
                    AdminServerDay(third, 4, 1, null, null, 0, 0, 3, 0, 2),
                ),
                routes = listOf(AdminRouteStats("sport", 14, 2, 1, 20, 90)),
                myItmo = listOf(AdminUpstreamStats("sportSchedule", 6, failures(MyItmoFailureKind.NETWORK to 1))),
                push = listOf(AdminPushStats("FRIEND_REQUEST", sent = 1, failed = 2, tokenGone = 3, noDevice = 4)),
            ),
            service.server(admin, first, third),
        )
    }

    @Test
    fun `before any counter every day is zeros and collectedSince is null`() {
        `when`(http.stats(period)).thenReturn(HttpStats(emptyList(), emptyList(), emptyList(), emptyList()))
        `when`(myItmo.stats(period)).thenReturn(MyItmoStats(emptyList(), emptyList(), emptyList()))
        `when`(push.stats(period)).thenReturn(PushStats(emptyList(), emptyList(), emptyList(), emptyList()))

        val stats = service.server(admin, first, third)

        assertEquals(null, stats.collectedSince)
        assertEquals(period.days.map { AdminServerDay(it, 0, 0, null, null, 0, 0, 0, 0, 0) }, stats.days)
        assertEquals(listOf(0, 0, 0), listOf(stats.routes.size, stats.myItmo.size, stats.push.size))
    }

    @Test
    fun `a non-admin is refused before any counter is read`() {
        assertFailsWith<PermissionDeniedException> { service.server(UUID.randomUUID(), null, null) }
        verifyNoInteractions(counters, http, push, myItmo)
    }

    private fun failures(vararg given: Pair<MyItmoFailureKind, Int>): Map<MyItmoFailureKind, Long> {
        val map = given.toMap()
        return MyItmoFailureKind.entries.associateWith { (map[it] ?: 0).toLong() }
    }
}
