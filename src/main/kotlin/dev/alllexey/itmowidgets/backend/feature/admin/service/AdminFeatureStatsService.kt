package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFeatureUsage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPairingFunnel
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPrivacyStats
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingFunnel
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingStep
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionStatistics
import dev.alllexey.itmowidgets.backend.feature.social.service.SocialStatistics
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The features tab of the statistics page: authorizes, resolves the period, asks every [UsageSource] for its row and
 * composes them with the figures of [SocialStatistics], [ConnectionStatistics] and [UserStatistics]. The ids of the
 * sources' users are united here, narrowed to accounts by [UserStatistics.accountsAmong] in one query (the placeholder
 * of a deleted account uses no feature, whichever source names it), and only their counts leave.
 */
@Service
class AdminFeatureStatsService(
    private val access: AdminAccess,
    sources: List<UsageSource>,
    private val social: SocialStatistics,
    private val connections: ConnectionStatistics,
    private val users: UserStatistics,
    private val clock: Clock,
) {
    /** By feature name, so the rows keep their order whatever order the beans come in. */
    private val sources = sources.sortedBy { it.feature }

    init {
        val repeated = this.sources.groupBy { it.feature }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Usage sources repeat the features $repeated" }
    }

    @Transactional(readOnly = true)
    fun features(adminId: UUID, from: LocalDate?, to: LocalDate?): AdminFeatureStats {
        access.requireAdmin(adminId)
        val period = StatsPeriod.requested(from, to, clock)
        val reported = sources.map { it to it.usage(period) }
        val accounts = users.accountsAmong(reported.flatMapTo(HashSet()) { (_, used) -> used.users })
        val usage = reported.map { (source, used) -> source to used.copy(users = used.users intersect accounts) }
        val socialStats = social.stats(period)
        val connectionStats = connections.stats(period)
        val userStats = users.stats()
        return AdminFeatureStats(
            from = period.from,
            to = period.to,
            users = userStats.accounts,
            usersWithFriends = socialStats.usersWithFriends,
            socialUsers = usage.filter { (source, _) -> source.ownAction }.flatMapTo(HashSet()) { (_, used) -> used.users }.size.toLong(),
            features = usage.map { (source, used) -> AdminFeatureUsage(source.feature, used.events, used.users.size.toLong()) },
            // Both series have one row per day of the period.
            days = socialStats.days.zip(connectionStats.days) { friends, clients ->
                AdminFeatureStatsDay(friends.day, friends.requests, friends.accepts, clients.created, clients.revoked)
            },
            privacy = userStats.privacy.let { AdminPrivacyStats(it.schedule, it.sport, it.friends) },
            pairing = connections.pairing(period).map { it.toWire() },
        )
    }

    private fun PairingFunnel.toWire() = AdminPairingFunnel(
        client = client,
        created = steps.getValue(PairingStep.CREATED),
        opened = steps.getValue(PairingStep.OPENED),
        approved = steps.getValue(PairingStep.APPROVED),
        claimed = steps.getValue(PairingStep.CLAIMED),
        expired = steps.getValue(PairingStep.EXPIRED),
    )
}
