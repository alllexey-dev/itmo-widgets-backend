package dev.alllexey.itmowidgets.backend.services

import java.util.concurrent.TimeUnit

/** Elapsed work uses a monotonic clock, independently of the academic timestamp/zone. */
internal fun elapsedSportUpdateMillis(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))
