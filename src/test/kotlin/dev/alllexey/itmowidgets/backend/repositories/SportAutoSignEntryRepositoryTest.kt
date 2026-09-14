package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager

/**
 * Persistence side of the forecast rule: the frozen key round-trips through PostgreSQL and the
 * lookup returns exactly the unresolved waiting entries, in creation order. The rule itself is
 * covered by SportQueueRulesTest; nothing here restates it.
 */
class SportAutoSignEntryRepositoryTest @Autowired constructor(
    private val repository: SportAutoSignEntryRepository,
    private val entityManager: TestEntityManager
) : PostgreSqlRepositoryTest() {

    private lateinit var user: User
    private lateinit var building: SportBuilding
    private lateinit var section: SportSection
    private lateinit var teacher: SportTeacher
    private lateinit var timeSlot: SportTimeSlot
    private var nextLessonId = 100L
    private var nextUserIsu = 900002

    @BeforeEach
    fun seedReferenceData() {
        user = entityManager.persist(User(isu = 900001, pictureUrl = null, name = "Test user").apply {
            settings = UserSettingsEntity(user = this)
        })
        building = entityManager.persist(SportBuilding(id = 1, name = "Building A"))
        section = entityManager.persist(SportSection(id = 2, name = "Section"))
        teacher = entityManager.persist(SportTeacher(isu = 3, name = "Teacher"))
        timeSlot = entityManager.persist(SportTimeSlot(id = 4, timeStart = "10:00", timeEnd = "11:00"))
    }

    @Test
    fun `only uncancelled unresolved waiting entries are returned in creation order`() {
        val prototype = prototype()
        val newerWaiting = entry(prototype, createdAt = CREATED_AT.plusSeconds(2))
        val olderWaiting = entry(prototype, createdAt = CREATED_AT)
        entry(prototype, createdAt = CREATED_AT.minusSeconds(1), cancelled = true)
        QueueEntryStatus.entries
            .filterNot { it == QueueEntryStatus.WAITING }
            .forEach { entry(prototype, it, CREATED_AT.minusSeconds(3)) }

        assertEquals(listOf(olderWaiting.id, newerWaiting.id), candidates())
    }

    @Test
    fun `an entry already bound to a real lesson is no longer an unresolved candidate`() {
        val prototype = prototype()
        val unresolved = entry(prototype)
        entry(prototype, realLesson = prototype)

        assertEquals(listOf(unresolved.id), candidates())
    }

    @ParameterizedTest
    @EnumSource(Criterion::class)
    fun `every lesson criterion must match`(criterion: Criterion) {
        val differentBuilding = entityManager.persist(SportBuilding(id = 11, name = "Building B"))
        val differentSection = entityManager.persist(SportSection(id = 12, name = "Other section"))
        val differentTeacher = entityManager.persist(SportTeacher(isu = 13, name = "Other teacher"))
        val differentTimeSlot = entityManager.persist(SportTimeSlot(id = 14, timeStart = "11:00", timeEnd = "12:00"))
        val prototype = prototype(
            buildingId = if (criterion == Criterion.BUILDING) differentBuilding.id else building.id,
            roomId = if (criterion == Criterion.ROOM) 99 else 10,
            section = if (criterion == Criterion.SECTION) differentSection else section,
            teacher = if (criterion == Criterion.TEACHER) differentTeacher else teacher,
            timeSlot = if (criterion == Criterion.TIME_SLOT) differentTimeSlot else timeSlot,
            sectionLevel = if (criterion == Criterion.SECTION_LEVEL) 2 else 1,
            lessonLevel = if (criterion == Criterion.LESSON_LEVEL) 2 else 1,
            typeId = if (criterion == Criterion.TYPE) 2 else 1,
            start = if (criterion == Criterion.START) PROTOTYPE_START.plusMinutes(1) else PROTOTYPE_START,
            end = if (criterion == Criterion.END) PROTOTYPE_START.plusHours(1).plusSeconds(1) else PROTOTYPE_START.plusHours(1),
        )
        entry(prototype)

        assertTrue(candidates().isEmpty())
    }

    @Test
    fun `renaming the same room does not prevent matching`() {
        val entry = entry(prototype(roomName = "Renamed room"))

        assertEquals(listOf(entry.id), candidates())
    }

    @Test
    fun `a prototype offset by any interval other than the forecast window is a different lesson`() {
        entry(prototype(start = PROTOTYPE_START.plusWeeks(1)))
        entry(prototype(start = PROTOTYPE_START.minusWeeks(2)))

        assertTrue(candidates().isEmpty())
    }

    @Test
    fun `the same moment stored in another offset still matches`() {
        val shifted = PROTOTYPE_START.withOffsetSameInstant(OffsetDateTime.now().offset)
        val entry = entry(prototype(start = shifted, end = shifted.plusHours(1)))

        assertEquals(listOf(entry.id), candidates())
    }

    @Test
    fun `a venue that proves nothing is stored without a key and is invisible to every lookup`() {
        entry(prototype(buildingId = 0, roomId = 10))
        entry(prototype(buildingId = null, roomId = 10))
        entry(prototype(buildingId = 1, roomId = -1))
        val matchable = entry(prototype())
        entityManager.flush()
        entityManager.clear()

        assertEquals(listOf(null, null, null), storedKeys().dropLast(1))
        assertEquals(listOf(matchable.id), candidates())
        assertTrue(candidates(buildingId = 0, roomId = 10).isEmpty())
    }

    @Test
    fun `explicit online rooms match nullable or minus one venue without matching offline rooms`() {
        val online = entry(prototype(buildingId = null, roomId = -1))
        val alternateOnline = entry(prototype(buildingId = -1, roomId = -1))
        entry(prototype())
        val expected = listOf(online.id, alternateOnline.id)

        assertEquals(expected, candidates(buildingId = null, roomId = -1))
        assertEquals(expected, candidates(buildingId = -1, roomId = -1))
    }

    @Test
    fun `external venue does not need a filter row but still needs exact room and venue`() {
        val external = entry(prototype(buildingId = 335, roomId = 20013))
        entry(prototype(buildingId = 493, roomId = 20013))
        entry(prototype(buildingId = 335, roomId = 21765))

        assertEquals(listOf(external.id), candidates(buildingId = 335, roomId = 20013))
    }

    /** Looks up the catalog lesson a default prototype predicts: same identity, two weeks later. */
    private fun candidates(buildingId: Long? = building.id, roomId: Long = 10): List<Long> {
        val matchKey = SportQueueRules.matchKey(predictedLesson(buildingId, roomId))
        entityManager.flush()
        entityManager.clear()
        return matchKey?.let { key -> repository.findUnresolvedCandidates(key).map { it.entryId } } ?: emptyList()
    }

    private fun storedKeys(): List<String?> = entityManager.entityManager
        .createNativeQuery("SELECT match_key FROM sport_auto_sign_entries ORDER BY id")
        .resultList
        .map { it as String? }

    private fun predictedLesson(buildingId: Long?, roomId: Long): SportLesson = SportLesson(
        id = 0,
        section = section,
        sectionLevel = 1,
        lessonLevel = 1,
        typeId = 1,
        sectionName = section.name,
        timeSlot = timeSlot,
        buildingId = buildingId,
        teacher = teacher,
        roomId = roomId,
        roomName = "Room",
        start = PROTOTYPE_START.plusWeeks(SportQueueRules.PREDICTION_WEEKS),
        end = PROTOTYPE_START.plusHours(1).plusWeeks(SportQueueRules.PREDICTION_WEEKS),
        lastSeenAt = CREATED_AT,
    )

    private fun prototype(
        buildingId: Long? = this.building.id,
        section: SportSection = this.section,
        teacher: SportTeacher = this.teacher,
        timeSlot: SportTimeSlot = this.timeSlot,
        sectionLevel: Long = 1,
        lessonLevel: Long = 1,
        typeId: Long = 1,
        start: OffsetDateTime = PROTOTYPE_START,
        end: OffsetDateTime = start.plusHours(1),
        roomId: Long = 10,
        roomName: String = "Room"
    ): SportLesson = entityManager.persist(SportLesson(
        id = nextLessonId++,
        section = section,
        sectionLevel = sectionLevel,
        lessonLevel = lessonLevel,
        typeId = typeId,
        sectionName = section.name,
        timeSlot = timeSlot,
        buildingId = buildingId,
        teacher = teacher,
        roomId = roomId,
        roomName = roomName,
        start = start,
        end = end,
        lastSeenAt = CREATED_AT,
    ))

    private fun entry(
        prototype: SportLesson,
        status: QueueEntryStatus = QueueEntryStatus.WAITING,
        createdAt: Instant = CREATED_AT,
        cancelled: Boolean = false,
        realLesson: SportLesson? = null,
    ): SportAutoSignEntity = entityManager.persist(SportAutoSignEntity(
        user = entityManager.persist(User(isu = nextUserIsu++, pictureUrl = null, name = "Test queue owner").apply {
            settings = UserSettingsEntity(user = this)
        }),
        prototypeLesson = prototype,
        realLesson = realLesson,
        status = status,
        isCancelled = cancelled,
        createdAt = createdAt
    ))

    enum class Criterion {
        BUILDING, ROOM, SECTION, TEACHER, TIME_SLOT, SECTION_LEVEL, LESSON_LEVEL, TYPE, START, END
    }

    companion object {
        private val PROTOTYPE_START = OffsetDateTime.parse("2026-09-08T10:00:00+03:00")
        private val CREATED_AT = Instant.parse("2026-09-01T09:00:00Z")
    }
}
