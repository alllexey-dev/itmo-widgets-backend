package dev.alllexey.itmowidgets.backend.feature.reviews.service

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The official teacher names from MyITMO, cached in memory only; the database never stores them. Staff see them
 * through [TeacherDisplayNames], which asks here for the teachers no Reviews copy names. Callers resolve names after
 * their transaction: the MyITMO source refuses to run inside one.
 */
@Service
class TeacherNamesService(private val source: OfficialPersonNamesSource, private val clock: Clock) {
    private data class CacheEntry(val name: String?, val retryAt: Instant)
    private val cache = object : LinkedHashMap<Int, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CacheEntry>): Boolean = size > MAX_ENTRIES
    }
    private val locks = Array(32) { Any() }

    @Volatile private var sourceRetryAt = Instant.MIN

    /** Null when the directory has no name or is unavailable and nothing was cached before. */
    fun name(isu: Int): String? = synchronized(locks[Math.floorMod(isu, locks.size)]) {
        val now = clock.instant()
        val previous = cached(isu)
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

    /**
     * Names of a list page, absent when unknown. Fresh cached names come at once; at most [PAGE_LOOKUPS] others are
     * looked up, in the order given, so a long page names its first rows and the rest on a later load. The others keep
     * a name cached before, if any. An outage costs one failed lookup, as in [name].
     */
    fun names(isus: Collection<Int>): Map<Int, String> {
        val now = clock.instant()
        val entries = isus.distinct().associateWith(::cached)
        val lookedUp = entries.filterValues { it == null || !now.isBefore(it.retryAt) }.keys.take(PAGE_LOOKUPS).associateWith(::name)
        return entries.mapNotNull { (isu, entry) ->
            (if (isu in lookedUp) lookedUp[isu] else entry?.name)?.let { isu to it }
        }.toMap()
    }

    private fun cached(isu: Int): CacheEntry? = synchronized(cache) { cache[isu] }

    private companion object {
        const val MAX_ENTRIES = 4096
        const val PAGE_LOOKUPS = 10
        val SUCCESS_TTL: Duration = Duration.ofHours(12)
        val RETRY_DELAY: Duration = Duration.ofSeconds(30)
    }
}
