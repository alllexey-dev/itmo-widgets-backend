package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager

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
    fun `same building and room return only uncancelled waiting and notified entries in creation order`() {
        val prototype = prototype()
        val newerWaiting = entry(prototype, createdAt = CREATED_AT.plusSeconds(2))
        val olderNotified = entry(prototype, QueueEntryStatus.NOTIFIED, CREATED_AT)
        entry(prototype, createdAt = CREATED_AT.minusSeconds(1), cancelled = true)
        entry(prototype, QueueEntryStatus.NOTIFIED, CREATED_AT.minusSeconds(2), cancelled = true)
        QueueEntryStatus.entries
            .filterNot { it == QueueEntryStatus.WAITING || it == QueueEntryStatus.NOTIFIED }
            .forEach { entry(prototype, it, CREATED_AT.minusSeconds(3)) }

        assertEquals(listOf(olderNotified.id, newerWaiting.id), matching().map { it.id })
    }

    @ParameterizedTest
    @EnumSource(Criterion::class)
    fun `every lesson criterion must match`(criterion: Criterion) {
        val differentBuilding = entityManager.persist(SportBuilding(id = 11, name = "Building B"))
        val differentSection = entityManager.persist(SportSection(id = 12, name = "Other section"))
        val differentTeacher = entityManager.persist(SportTeacher(isu = 13, name = "Other teacher"))
        val differentTimeSlot = entityManager.persist(SportTimeSlot(id = 14, timeStart = "11:00", timeEnd = "12:00"))
        val prototype = prototype(
            building = if (criterion == Criterion.BUILDING) differentBuilding else building,
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
        entry(prototype, QueueEntryStatus.NOTIFIED)

        assertTrue(matching().isEmpty())
    }

    @Test
    fun `renaming the same room does not prevent matching`() {
        val entry = entry(prototype(roomName = "Renamed room"))

        assertEquals(listOf(entry.id), matching().map { it.id })
    }

    @Test
    fun `unknown fallback buildings cannot match even when both ids are zero`() {
        val unknownBuilding = entityManager.persist(SportBuilding(id = 0, name = "Unknown"))
        entry(prototype(building = unknownBuilding))
        entry(prototype(building = unknownBuilding), QueueEntryStatus.NOTIFIED)
        entry(prototype())

        assertTrue(matching(buildingId = 0).isEmpty())
    }

    private fun matching(buildingId: Long = building.id): List<SportAutoSignEntity> {
        entityManager.flush()
        entityManager.clear()
        return repository.findMatchingWaitingEntries(
            sectionId = section.id,
            teacherId = teacher.isu,
            buildingId = buildingId,
            roomId = 10,
            sectionLevel = 1,
            lessonLevel = 1,
            typeId = 1,
            timeSlotId = timeSlot.id,
            prototypeStart = PROTOTYPE_START,
            prototypeEnd = PROTOTYPE_START.plusHours(1),
        )
    }

    private fun prototype(
        building: SportBuilding = this.building,
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
        building = building,
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
        cancelled: Boolean = false
    ): SportAutoSignEntity = entityManager.persist(SportAutoSignEntity(
        user = entityManager.persist(User(isu = nextUserIsu++, pictureUrl = null, name = "Test queue owner").apply {
            settings = UserSettingsEntity(user = this)
        }),
        prototypeLesson = prototype,
        realLesson = null,
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
