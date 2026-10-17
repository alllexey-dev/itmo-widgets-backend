package dev.alllexey.itmowidgets.backend.feature.sport.service

import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
private val MIN_LESSON_LENGTH: Duration = Duration.ofMinutes(15)
private val MAX_LESSON_LENGTH: Duration = Duration.ofHours(6)

/**
 * The lesson end with My ITMO's inconsistent `date_end` repaired, the rule of MyItmoApi 2.1.1
 * `normalizedSportLessonEnd` (this service maps wire rows itself; docs/contracts/sport-automation.md).
 * My ITMO sometimes sends a `date_end` on the wrong date with a plausible time (a weekly series ending two days
 * before it starts). A [rawEnd] after [start] on the same Moscow date is returned unchanged. Otherwise the end
 * is rebuilt on the start's Moscow date from [slotEnd] (`HH:mm`, Moscow) or, when that does not parse, the raw
 * end's Moscow time of day; a rebuilt end 15 minutes to 6 hours after [start] is returned, any other is null.
 */
internal fun normalizedLessonEnd(start: OffsetDateTime, rawEnd: OffsetDateTime, slotEnd: String?): OffsetDateTime? {
    val day = start.atZoneSameInstant(MOSCOW).toLocalDate()
    val raw = rawEnd.atZoneSameInstant(MOSCOW)
    if (rawEnd.isAfter(start) && raw.toLocalDate() == day) return rawEnd
    val time = slotEnd?.let(::parseTime) ?: raw.toLocalTime()
    val end = day.atTime(time).atZone(MOSCOW).toOffsetDateTime().withOffsetSameInstant(rawEnd.offset)
    val length = Duration.between(start, end)
    return end.takeIf { length >= MIN_LESSON_LENGTH && length <= MAX_LESSON_LENGTH }
}

private fun parseTime(text: String): LocalTime? = try {
    LocalTime.parse(text.trim())
} catch (unparsable: DateTimeParseException) {
    null
}
