package dev.alllexey.itmowidgets.backend.feature.social.service

import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipStatsDay
import dev.alllexey.itmowidgets.backend.feature.social.model.SocialStats
import dev.alllexey.itmowidgets.backend.feature.social.persistence.SocialStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The friendship figures of a statistics period: three aggregate queries in one read-only transaction, no rows loaded. */
@Service
class SocialStatistics(private val repository: SocialStatisticsRepository) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): SocialStats {
        val requests = period.zeroFilled(repository.requestsPerDay(period))
        val accepts = period.zeroFilled(repository.acceptsPerDay(period))
        return SocialStats(
            usersWithFriends = repository.usersWithFriends(),
            days = requests.mapIndexed { index, day -> FriendshipStatsDay(day.day, day.count, accepts[index].count) },
        )
    }
}
