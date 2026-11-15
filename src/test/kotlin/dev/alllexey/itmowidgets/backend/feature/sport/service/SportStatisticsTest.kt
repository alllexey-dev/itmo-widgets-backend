package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportDayEntries
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome.CANCELLED
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome.EXPIRED
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome.GAVE_UP_NOTIFYING
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome.NOTIFIED
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome.SATISFIED
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome.WAITING
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStopReason
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSlotStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportWaitTimes
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportStatisticsRepository
import dev.alllexey.itmowidgets.backend.feature.sport.web.QueueEntryStatus
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every sport aggregate on real PostgreSQL with synthetic entries in 2031, a year no other test writes to; figures that
 * are the state now (subscriptions) are compared as deltas. Moscow is UTC+3: its day starts at 21:00 UTC.
 */
@Import(
    SportStatistics::class,
    SportStatisticsRepository::class,
    SportAutoSignUsage::class,
    SportFreeSignUsage::class,
    SportSectionsUsage::class,
)
class SportStatisticsTest @Autowired constructor(
    private val statistics: SportStatistics,
    private val sources: List<UsageSource>,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2031-03-01"), LocalDate.parse("2031-03-07"))
    private var nextIsu = 983_100
    private var nextSubscription = 0
    private var nextLesson = 983_001L

    @BeforeEach
    fun catalog() {
        jdbc.update("INSERT INTO sport_sections (id, name) VALUES ($SECTION, 'Stat Swim') ON CONFLICT DO NOTHING")
        jdbc.update("INSERT INTO sport_teachers (isu, name) VALUES ($SECTION, 'Synthetic teacher') ON CONFLICT DO NOTHING")
        jdbc.update("INSERT INTO sport_time_slots (id, time_start, time_end) VALUES ($SECTION, '10:00', '11:30') ON CONFLICT DO NOTHING")
    }

    @Test
    fun `an empty period has a zero day for every day, every outcome key and no times, sections or slots`() {
        val empty = StatsPeriod(LocalDate.parse("2031-06-01"), LocalDate.parse("2031-06-03"))
        val stats = statistics.stats(empty)

        assertEquals(empty.days, stats.days.map { it.day })
        assertTrue(stats.days.all { it.autoSign == ZERO && it.freeSign == ZERO && it.signUps == 0L })
        for (entries in listOf(stats.autoSign, stats.freeSign)) {
            assertEquals(0, entries.created)
            assertEquals(SportEntryOutcome.entries.associateWith { 0L }, entries.outcomes)
            assertEquals(SportWaitTimes.NONE, entries.waits)
        }
        assertEquals(emptyList(), stats.sections)
        assertEquals(emptyList(), stats.slots)
        assertEquals(0, stats.subscriptions.created)
        assertEquals(SportSectionOccurrenceState.entries.associateWith { 0L }, stats.subscriptions.occurrences)
        assertEquals(SportSectionStopReason.entries.toSet(), stats.subscriptions.stopped.keys)
        sources.forEach { assertEquals(FeatureUsage(0, emptySet()), it.usage(empty), it.feature) }
    }

    @Test
    fun `entries count by Moscow creation day, outcome, wait, section and slot`() {
        val (first, second, third) = List(3) { user() }
        val (swim, otherSwim) = List(2) { lesson() }

        // Auto entries: created in the period unless noted.
        auto(
            first,
            "2031-02-28T21:00:00Z",
            QueueEntryStatus.SATISFIED,
            MONDAY_10,
            notified = "2031-03-02T10:00:00Z",
            satisfied = "2031-03-02T10:01:40Z",
        )
        auto(third, "2031-02-28T20:59:59Z", QueueEntryStatus.SATISFIED, MONDAY_10, satisfied = "2031-03-01T09:00:00Z") // before the period
        auto(first, "2031-03-03T12:00:00Z", QueueEntryStatus.SATISFIED, MONDAY_10, cancelled = true, satisfied = "2031-03-03T13:00:00Z")
        auto(first, "2031-03-03T20:59:00Z", QueueEntryStatus.SATISFIED, MONDAY_10, satisfied = "2031-03-04T05:00:00Z") // not timed
        auto(first, "2031-03-07T20:59:59Z", QueueEntryStatus.WAITING, MONDAY_0130)
        auto(third, "2031-03-07T21:00:00Z", QueueEntryStatus.SATISFIED, MONDAY_0130, satisfied = "2031-03-07T21:00:00Z") // after it
        auto(
            second,
            "2031-03-05T09:00:00Z",
            QueueEntryStatus.SATISFIED,
            WEDNESDAY_1845,
            section = "Stat Gym",
            notified = "2031-03-05T10:00:00Z",
            satisfied = "2031-03-05T10:05:00Z",
        )
        auto(second, "2031-03-02T10:00:00Z", QueueEntryStatus.EXPIRED, MONDAY_0130)
        auto(second, "2031-03-04T10:00:00Z", QueueEntryStatus.GAVE_UP_NOTIFYING, WEDNESDAY_1845)
        auto(second, "2031-03-06T10:00:00Z", QueueEntryStatus.NOTIFIED, WEDNESDAY_1845, notified = "2031-03-06T11:00:00Z")
        // Queue entries.
        free(first, swim, "2031-03-02T08:00:00Z", QueueEntryStatus.SATISFIED, satisfied = "2031-03-02T09:00:00Z")
        free(third, swim, "2031-03-02T08:00:00Z", QueueEntryStatus.WAITING, cancelled = true)
        free(third, otherSwim, "2031-03-06T10:00:00Z", QueueEntryStatus.EXPIRED)
        free(second, swim, "2031-02-20T10:00:00Z", QueueEntryStatus.SATISFIED, satisfied = "2031-03-06T22:00:00Z") // before

        val stats = statistics.stats(period)

        assertEquals(period.days, stats.days.map { it.day })
        assertEquals(
            listOf(1L to 1L, 1L to 0L, 2L to 1L, 1L to 0L, 1L to 1L, 1L to 0L, 1L to 0L),
            stats.days.map { it.autoSign.created to it.autoSign.satisfied },
        )
        assertEquals(
            listOf(0L to 0L, 2L to 1L, 0L to 0L, 0L to 0L, 0L to 0L, 1L to 0L, 0L to 0L),
            stats.days.map { it.freeSign.created to it.freeSign.satisfied },
        )
        // Satisfied on the day whenever created, a cancelled one included; the Moscow day of 22:00 UTC is the next one.
        assertEquals(listOf(1L, 2L, 1L, 1L, 1L, 0L, 1L), stats.days.map { it.signUps })

        assertEquals(8, stats.autoSign.created)
        assertEquals(
            outcomes(SATISFIED to 3, CANCELLED to 1, WAITING to 1, EXPIRED to 1, GAVE_UP_NOTIFYING to 1, NOTIFIED to 1),
            stats.autoSign.outcomes,
        )
        // From the first notification: 100 s and 300 s; the entry without one is not timed.
        assertEquals(SportWaitTimes(timed = 2, medianSeconds = 200, p90Seconds = 280), stats.autoSign.waits)
        assertEquals(3, stats.freeSign.created)
        assertEquals(outcomes(SATISFIED to 1, CANCELLED to 1, EXPIRED to 1), stats.freeSign.outcomes)
        // From the creation.
        assertEquals(SportWaitTimes(timed = 1, medianSeconds = 3600, p90Seconds = 3600), stats.freeSign.waits)

        // Waits from creation: 133300 s, 28860 s and 3600 s for the swim, 3900 s for the gym.
        assertEquals(
            listOf(
                SportSectionStats("Stat Swim", autoSignEntries = 7, freeSignEntries = 3, satisfied = 3, medianWaitSeconds = 28860),
                SportSectionStats("Stat Gym", autoSignEntries = 1, freeSignEntries = 0, satisfied = 1, medianWaitSeconds = 3900),
            ),
            stats.sections,
        )
        // Monday 01:30 in Moscow is still Sunday in UTC.
        assertEquals(
            listOf(
                SportSlotStats(DayOfWeek.MONDAY, LocalTime.of(1, 30), entries = 2, satisfied = 0),
                SportSlotStats(DayOfWeek.MONDAY, LocalTime.of(10, 0), entries = 3, satisfied = 2),
                SportSlotStats(DayOfWeek.WEDNESDAY, LocalTime.of(18, 45), entries = 3, satisfied = 1),
            ),
            stats.slots,
        )

        val usage = sources.associate { it.feature to it.usage(period) }
        assertEquals(setOf("SPORT_AUTO_SIGN", "SPORT_FREE_SIGN", "SPORT_SECTIONS"), usage.keys)
        assertEquals(FeatureUsage(8, setOf(first, second)), usage["SPORT_AUTO_SIGN"])
        assertEquals(FeatureUsage(3, setOf(first, third)), usage["SPORT_FREE_SIGN"])
    }

    @Test
    fun `a section without a satisfied entry has no median and at most 50 sections are listed by entries`() {
        val user = user()
        for (index in 1..51) {
            auto(user, "2031-03-02T10:00:00Z", QueueEntryStatus.WAITING, MONDAY_10, section = "Cap %02d".format(index))
        }
        auto(user, "2031-03-02T10:00:00Z", QueueEntryStatus.EXPIRED, MONDAY_10, section = "Cap 51")

        val sections = statistics.stats(period).sections

        assertEquals(50, sections.size)
        assertEquals(SportSectionStats("Cap 51", 2, 0, 0, null), sections.first())
        assertEquals((1..49).map { "Cap %02d".format(it) }, sections.drop(1).map { it.sectionName })
        assertTrue(sections.all { it.medianWaitSeconds == null })
    }

    @Test
    fun `subscriptions are counted as they are now and their occurrences by start in the period`() {
        val before = statistics.stats(period).subscriptions
        val (first, second, third) = List(3) { user() }
        val active = subscription(first, "2031-03-02T10:00:00Z")
        val expired = subscription(second, "2031-03-04T10:00:00Z", SportSectionStopReason.EXPIRED)
        subscription(third, "2031-02-01T10:00:00Z", SportSectionStopReason.USER)
        subscription(third, "2031-03-07T21:00:00Z")
        occurrence(active, "2031-03-03T07:00:00Z", SportSectionOccurrenceState.QUEUED)
        occurrence(active, "2031-03-05T07:00:00Z", SportSectionOccurrenceState.LIMIT)
        occurrence(expired, "2031-02-28T21:00:00Z", SportSectionOccurrenceState.SKIPPED)
        occurrence(expired, "2031-02-28T20:59:00Z", SportSectionOccurrenceState.QUEUED)
        occurrence(expired, "2031-03-07T21:00:00Z", SportSectionOccurrenceState.LIMIT)

        val after = statistics.stats(period).subscriptions

        assertEquals(2, after.active - before.active)
        assertEquals(
            mapOf(SportSectionStopReason.USER to 1L, SportSectionStopReason.EXPIRED to 1L),
            SportSectionStopReason.entries.associateWith { after.stopped.getValue(it) - before.stopped.getValue(it) },
        )
        assertEquals(2, after.created - before.created)
        assertEquals(
            SportSectionOccurrenceState.entries.associateWith { 1L },
            SportSectionOccurrenceState.entries.associateWith { after.occurrences.getValue(it) - before.occurrences.getValue(it) },
        )
        val usage = sources.single { it.feature == "SPORT_SECTIONS" }.usage(period)
        assertEquals(FeatureUsage(2, setOf(first, second)), usage)
    }

    /** A catalog lesson; its name carries spaces, and queue entries count under the trimmed name auto entries store. */
    private fun lesson(): Long {
        val id = nextLesson++
        jdbc.update(
            """
            INSERT INTO sport_lessons (id, section_id, section_level, lesson_level, type_id, section_name, time_slot_id,
                building_id, teacher_isu, room_id, room_name, starts_at, ends_at, last_seen_at)
            VALUES (?, $SECTION, 1, 1, 1, ' Stat Swim ', $SECTION, NULL, $SECTION, 1, 'Synthetic room', ?, ?, ?)
            """,
            id,
            at("2031-03-10T07:00:00Z"),
            at("2031-03-10T08:30:00Z"),
            at("2031-03-01T00:00:00Z"),
        )
        return id
    }

    private fun user(): UUID = jdbc.insertUser(nextIsu++, createdAt = at("2031-01-01T00:00:00Z").toInstant())

    private fun auto(
        user: UUID,
        created: String,
        status: QueueEntryStatus,
        target: String,
        section: String = "Stat Swim",
        cancelled: Boolean = false,
        notified: String? = null,
        satisfied: String? = null,
    ) {
        val start = at(target)
        val end = Timestamp.from(start.toInstant().plusSeconds(5400))
        jdbc.update(
            """
            INSERT INTO sport_auto_sign_entries (user_id, prototype_lesson_id, target_section_id, target_section_name,
                target_section_level, target_lesson_level, target_type_id, target_time_slot_id, target_teacher_isu,
                target_teacher_name, target_room_id, target_room_name, target_starts_at, target_ends_at,
                predicted_starts_at, predicted_ends_at, status, is_cancelled, created_at, first_notified_at, satisfied_at)
            VALUES (?, ?, $SECTION, ?, 1, 1, 1, $SECTION, $SECTION, 'Synthetic teacher', 1, 'Synthetic room', ?, ?, ?, ?,
                ?, ?, ?, ?, ?)
            """,
            user, lesson(), section, start, end, start, end, status.name, cancelled, at(created), notified?.let(::at), satisfied?.let(::at),
        )
    }

    private fun free(
        user: UUID,
        lesson: Long,
        created: String,
        status: QueueEntryStatus,
        cancelled: Boolean = false,
        satisfied: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO sport_free_sign_entries (user_id, lesson_id, status, is_cancelled, created_at, satisfied_at, force_sign)
            VALUES (?, ?, ?, ?, ?, ?, false)
            """,
            user,
            lesson,
            status.name,
            cancelled,
            at(created),
            satisfied?.let(::at),
        )
    }

    private fun subscription(user: UUID, created: String, stopped: SportSectionStopReason? = null): Long = jdbc.queryForObject(
        """
        INSERT INTO sport_section_subscriptions (user_id, slot_key, source_lesson_id, last_opened_ends_at, status, stop_reason,
            stopped_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """,
        Long::class.java,
        user, "stats-slot-${nextSubscription++}", lesson(), at("2031-03-10T08:30:00Z"), if (stopped == null) "ACTIVE" else "STOPPED",
        stopped?.name, stopped?.let { at(created) }, at(created),
    )!!

    private fun occurrence(subscription: Long, start: String, state: SportSectionOccurrenceState) {
        val starts = at(start)
        jdbc.update(
            "INSERT INTO sport_section_occurrences (subscription_id, starts_at, ends_at, state) VALUES (?, ?, ?, ?)",
            subscription,
            starts,
            Timestamp.from(starts.toInstant().plusSeconds(5400)),
            state.name,
        )
    }

    /** Every outcome, 0 unless given. */
    private fun outcomes(vararg counts: Pair<SportEntryOutcome, Int>): Map<SportEntryOutcome, Long> {
        val given = counts.toMap()
        return SportEntryOutcome.entries.associateWith { (given[it] ?: 0).toLong() }
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))

    private companion object {
        const val SECTION = 983_001L
        val ZERO = SportDayEntries(0, 0)

        // Target lesson starts as UTC instants: Monday 10:00, Monday 01:30 and Wednesday 18:45 in Moscow.
        const val MONDAY_10 = "2031-03-10T07:00:00Z"
        const val MONDAY_0130 = "2031-03-09T22:30:00Z"
        const val WEDNESDAY_1845 = "2031-03-12T15:45:00Z"
    }
}
