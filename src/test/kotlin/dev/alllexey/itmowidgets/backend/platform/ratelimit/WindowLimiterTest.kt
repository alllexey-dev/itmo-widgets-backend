package dev.alllexey.itmowidgets.backend.platform.ratelimit

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowLimiterTest {
    @Test
    fun `each key gets the limit per window and a new window starts over`() {
        var now = Instant.parse("2026-10-12T09:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now

            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val limiter = WindowLimiter(2, Duration.ofMinutes(1), clock)

        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        now = now.plusSeconds(60)
        assertTrue(limiter.tryAcquire("a"))
    }
}
