package dev.alllexey.itmowidgets.backend.testing

import org.mockito.ArgumentMatchers
import java.time.Instant

/** Mockito matchers return null, which a non-null Kotlin `Instant` parameter cannot take. */
object InstantMatchers {
    fun anyInstant(): Instant = ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH

    fun eqInstant(value: Instant): Instant = ArgumentMatchers.eq(value) ?: value
}
