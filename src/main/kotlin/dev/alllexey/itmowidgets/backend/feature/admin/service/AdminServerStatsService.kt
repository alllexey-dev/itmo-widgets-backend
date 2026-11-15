package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPushStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRouteStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServerDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServerStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUpstreamStats
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoStatistics
import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome
import dev.alllexey.itmowidgets.backend.feature.push.service.PushStatistics
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.metrics.HttpStatistics
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The server tab of the statistics page, over the daily counters: authorizes, resolves the period, zero-fills the
 * daily series and maps. The HTTP figures come from [HttpStatistics], the pushes and the My ITMO calls from the
 * features that count them.
 */
@Service
class AdminServerStatsService(
    private val access: AdminAccess,
    private val counters: DailyCounterReader,
    private val http: HttpStatistics,
    private val push: PushStatistics,
    private val myItmo: MyItmoStatistics,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun server(adminId: UUID, from: LocalDate?, to: LocalDate?): AdminServerStats {
        access.requireAdmin(adminId)
        val period = StatsPeriod.requested(from, to, clock)
        val httpStats = http.stats(period)
        val pushStats = push.stats(period)
        val myItmoStats = myItmo.stats(period)
        val requests = period.zeroFilled(httpStats.requests)
        val errors5xx = period.zeroFilled(httpStats.errors5xx)
        val latency = httpStats.latency.associateBy { it.day }
        val calls = period.zeroFilled(myItmoStats.calls)
        val failures = period.zeroFilled(myItmoStats.failures)
        val sent = period.zeroFilled(pushStats.sent)
        val failed = period.zeroFilled(pushStats.failed)
        val tokenGone = period.zeroFilled(pushStats.tokenGone)
        return AdminServerStats(
            from = period.from,
            to = period.to,
            collectedSince = counters.firstDay(),
            days = period.days.mapIndexed { index, day ->
                AdminServerDay(
                    date = day,
                    requests = requests[index].count,
                    errors5xx = errors5xx[index].count,
                    p50Millis = latency[day]?.p50Millis,
                    p95Millis = latency[day]?.p95Millis,
                    myItmoCalls = calls[index].count,
                    myItmoFailures = failures[index].count,
                    pushSent = sent[index].count,
                    pushFailed = failed[index].count,
                    pushTokenGone = tokenGone[index].count,
                )
            },
            routes = httpStats.routes.map {
                AdminRouteStats(it.group, it.requests, it.errors4xx, it.errors5xx, it.p50Millis, it.p95Millis)
            },
            myItmo = myItmoStats.operations.map { AdminUpstreamStats(it.operation, it.calls, it.failures) },
            push = pushStats.types.map {
                AdminPushStats(
                    type = it.type,
                    sent = it.outcomes.getValue(PushOutcome.SENT),
                    failed = it.outcomes.getValue(PushOutcome.FAILED),
                    tokenGone = it.outcomes.getValue(PushOutcome.TOKEN_GONE),
                    noDevice = it.outcomes.getValue(PushOutcome.NO_DEVICE),
                )
            },
        )
    }
}
