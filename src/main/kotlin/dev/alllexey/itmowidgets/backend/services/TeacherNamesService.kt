package dev.alllexey.itmowidgets.backend.services

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Teacher names for moderators, cached in memory only; the database never stores them. Callers resolve names
 * after their transaction: the MyITMO source refuses to run inside one.
 */
@Service
class TeacherNamesService(
    private val source: OfficialPersonNamesSource,
    private val clock: Clock,
) {
    private data class CacheEntry(val name: String?, val retryAt: Instant)
    private val cache = object : LinkedHashMap<Int, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CacheEntry>): Boolean = size > MAX_ENTRIES
    }
    private val locks = Array(32) { Any() }
    @Volatile private var sourceRetryAt = Instant.MIN

    /** Null when the directory has no name or is unavailable and nothing was cached before. */
    fun name(isu: Int): String? = synchronized(locks[Math.floorMod(isu, locks.size)]) {
        val now = clock.instant()
        val previous = synchronized(cache) { cache[isu] }
        if (previous != null && now.isBefore(previous.retryAt)) return@synchronized previous.name
        // An outage must not cause a separate timeout for every case in the queue.
        if (now.isBefore(sourceRetryAt)) return@synchronized previous?.name
        val entry = try {
            CacheEntry(source.name(isu), clock.instant().plus(SUCCESS_TTL))
        } catch (failure: PersonNameUnavailable) {
            val retryAt = clock.instant().plus(RETRY_DELAY)
            if (failure.temporary) sourceRetryAt = retryAt
            CacheEntry(previous?.name, retryAt)
        }
        synchronized(cache) { cache[isu] = entry }
        entry.name
    }

    private companion object {
        const val MAX_ENTRIES = 4096
        val SUCCESS_TTL: Duration = Duration.ofHours(12)
        val RETRY_DELAY: Duration = Duration.ofSeconds(30)
    }
}
