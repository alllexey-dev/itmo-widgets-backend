package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.users.model.UserStats
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The account figures now: two aggregate queries in one read-only transaction, no users loaded. */
@Service
class UserStatistics(private val repository: UserStatisticsRepository) {
    @Transactional(readOnly = true)
    fun stats(): UserStats = UserStats(accounts = repository.accounts(), privacy = repository.privacy())
}
