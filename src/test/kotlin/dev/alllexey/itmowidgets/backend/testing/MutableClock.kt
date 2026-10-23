package dev.alllexey.itmowidgets.backend.testing

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock a test moves by setting [now]; it starts at 2026-09-24T09:00:00Z. */
class MutableClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2026-09-24T09:00:00Z")

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
}
