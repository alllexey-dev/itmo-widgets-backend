package dev.alllexey.itmowidgets.backend.testing

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The fixed clock of test entities whose creation time the test does not look at. Entities have no `now()` default,
 * so a test that compares times passes its own instant instead.
 */
object TestClock {
    val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC)

    fun now(): Instant = Instant.now(CLOCK)
}
