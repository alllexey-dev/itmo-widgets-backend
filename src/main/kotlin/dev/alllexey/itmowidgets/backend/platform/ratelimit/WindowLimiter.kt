package dev.alllexey.itmowidgets.backend.platform.ratelimit

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * At most [limit] acquisitions per key in each fixed [window] of the clock.
 * shortcut: in memory per instance, so two replicas each allow the limit; move to the database or Redis before scaling out.
 */
class WindowLimiter(private val limit: Int, private val window: Duration, private val clock: Clock) {
    private data class Window(val start: Long, val count: Int)

    private val windows = ConcurrentHashMap<Any, Window>()

    fun tryAcquire(key: Any): Boolean {
        val now = clock.millis()
        val start = now - Math.floorMod(now, window.toMillis())
        var allowed = false
        windows.compute(key) { _, old ->
            val current = old?.takeIf { it.start == start } ?: Window(start, 0)
            allowed = current.count < limit
            if (allowed) current.copy(count = current.count + 1) else current
        }
        if (windows.size > MAX_KEYS) windows.values.removeIf { it.start < start }
        return allowed
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
