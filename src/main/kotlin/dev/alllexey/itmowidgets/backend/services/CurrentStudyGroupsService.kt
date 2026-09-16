package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.dto.UserProfile
import dev.alllexey.itmowidgets.core.model.GroupData
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Read-only group decoration after social authorization and the database transaction. */
@Service
class CurrentStudyGroupsService(
    private val source: OfficialStudyGroupsSource,
    private val clock: Clock,
) {
    private data class CacheEntry(val groups: List<OfficialStudyGroup>?, val retryAt: Instant)
    private val cache = object : LinkedHashMap<Int, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CacheEntry>): Boolean = size > MAX_ENTRIES
    }
    private val locks = Array(32) { Any() }
    @Volatile private var sourceRetryAt = Instant.MIN

    fun userData(user: UserData): UserData {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Resolve groups after authorization transaction" }
        val current = current(user.isu) ?: return user
        return user.copy(groups = current.map { group ->
            GroupData(group.name, group.course,
                user.groups.firstOrNull { it.name.trim() == group.name }?.facultyShortName
                    ?.takeIf(String::isNotBlank) ?: group.facultyName)
        }.distinct())
    }

    fun profile(profile: UserProfile): UserProfile = profile.copy(user = userData(profile.user))
    fun profiles(profiles: List<UserProfile>): List<UserProfile> = profiles.map(::profile)

    private fun current(isu: Int): List<OfficialStudyGroup>? = synchronized(locks[Math.floorMod(isu, locks.size)]) {
        val now = clock.instant()
        val previous = synchronized(cache) { cache[isu] }
        if (previous != null && now.isBefore(previous.retryAt)) return@synchronized previous.groups
        // An outage must not cause a separate timeout for every row in the list.
        if (now.isBefore(sourceRetryAt)) return@synchronized previous?.groups
        val entry = try {
            CacheEntry(source.load(isu), clock.instant().plus(SUCCESS_TTL))
        } catch (failure: StudyGroupsUnavailable) {
            val retryAt = clock.instant().plus(RETRY_DELAY)
            if (failure.temporary) sourceRetryAt = retryAt
            CacheEntry(previous?.groups, retryAt)
        }
        synchronized(cache) { cache[isu] = entry }
        entry.groups
    }

    private companion object {
        const val MAX_ENTRIES = 4096
        val SUCCESS_TTL: Duration = Duration.ofMinutes(30)
        val RETRY_DELAY: Duration = Duration.ofSeconds(30)
    }
}
