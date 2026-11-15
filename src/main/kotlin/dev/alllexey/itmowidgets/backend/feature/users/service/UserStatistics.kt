package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.users.model.AccountGroups
import dev.alllexey.itmowidgets.backend.feature.users.model.UserGrowth
import dev.alllexey.itmowidgets.backend.feature.users.model.UserStats
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The account figures for statistics, each a fixed number of aggregate queries in a read-only transaction. An account
 * is what [UserStatisticsRepository] says it is: the placeholder of a deleted account is none, so a deleted account
 * leaves every figure here.
 */
@Service
class UserStatistics(private val repository: UserStatisticsRepository) {
    /** The accounts and their privacy settings now. */
    @Transactional(readOnly = true)
    fun stats(): UserStats = UserStats(accounts = repository.accounts(), privacy = repository.privacy())

    /** The accounts created before [period] and per day of it. */
    @Transactional(readOnly = true)
    fun growth(period: StatsPeriod): UserGrowth =
        UserGrowth(before = repository.createdBefore(period), created = repository.createdPerDay(period))

    /** Every account by faculty and course, with how many of each are among [selected]. */
    @Transactional(readOnly = true)
    fun groups(selected: Set<UUID>): AccountGroups = repository.groups(selected)

    /** The registration time of every account created in `[from, until)`. */
    @Transactional(readOnly = true)
    fun registrations(from: Instant, until: Instant): Map<UUID, Instant> = repository.registrations(from, until)

    /** The ids of the accounts of [isus], for a feature whose rows name their user by ISU. */
    @Transactional(readOnly = true)
    fun accountIds(isus: Collection<Int>): Set<UUID> = repository.idsByIsu(isus)
}
