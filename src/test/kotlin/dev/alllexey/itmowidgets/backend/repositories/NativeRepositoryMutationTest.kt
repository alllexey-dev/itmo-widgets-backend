package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.GroupEntity
import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.backend.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.model.UserSportLesson
import dev.alllexey.itmowidgets.backend.services.LessonService
import dev.alllexey.itmowidgets.backend.services.LessonService.Companion.toEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Import(LessonService::class)
class NativeRepositoryMutationTest @Autowired constructor(
    private val faculties: FacultyRepository,
    private val qualifications: QualificationRepository,
    private val groups: GroupRepository,
    private val users: UserRepository,
    private val bookings: UserSportLessonRepository,
    private val lessons: LessonRepository,
    private val lessonService: LessonService,
    private val em: TestEntityManager,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {

    @Test
    fun `reference upserts replace metadata without replacing identities or membership`() {
        faculties.upsert(1, "Faculty", "F")
        qualifications.upsert(2, "Qualification")
        val groupId = UUID.randomUUID()
        groups.upsert(groupId, "M3100", 1, 1, 2)
        val user = user(900001).apply {
            groups.add(em.find(GroupEntity::class.java, groupId))
        }
        em.flush()
        em.clear()

        faculties.upsert(1, "Renamed faculty", "RF")
        qualifications.upsert(2, "Renamed qualification")
        groups.upsert(groupId, "M3200", 2, 1, 2)
        groups.upsert(groupId, "M3200", 2, 1, 2)
        em.clear()

        val savedGroup = groups.findById(groupId).orElseThrow()
        assertEquals("M3200", savedGroup.name)
        assertEquals(2, savedGroup.course)
        assertEquals("Renamed faculty", savedGroup.faculty.name)
        assertEquals("RF", savedGroup.faculty.shortName)
        assertEquals("Renamed qualification", savedGroup.qualification.name)
        assertEquals(setOf(groupId), users.findById(user.id).orElseThrow().groups.map { it.id }.toSet())
        assertEquals(1L, faculties.count())
        assertEquals(1L, qualifications.count())
        assertEquals(1L, groups.count())

        faculties.upsert(3, "Other faculty", "OF")
        qualifications.upsert(4, "Other qualification")
        groups.upsert(groupId, "M3300", 3, 3, 4)
        em.clear()
        val reassigned = groups.findById(groupId).orElseThrow()
        assertEquals(3L, reassigned.faculty.id)
        assertEquals(4L, reassigned.qualification.code)
        assertEquals(setOf(groupId), users.findById(user.id).orElseThrow().groups.map { it.id }.toSet())
        assertEquals(1L, groups.count())
    }

    @Test
    fun `duplicate settings insert preserves audience choices and auto sign limit`() {
        val owner = user(900001)
        owner.settings.apply {
            autoSignLimit = 7
            sportVisibility = SharingVisibility.NOBODY
            scheduleVisibility = SharingVisibility.ALL
        }
        em.flush()
        em.clear()

        assertEquals(0, users.insertSettingsIgnore(owner.id))

        val settings = em.find(UserSettingsEntity::class.java, owner.id)
        assertEquals(7, settings.autoSignLimit)
        assertEquals(SharingVisibility.NOBODY, settings.sportVisibility)
        assertEquals(SharingVisibility.ALL, settings.scheduleVisibility)
    }

    @Test
    fun `user insert is idempotent by ISU without replacing existing identity or settings`() {
        val id = UUID.randomUUID()
        assertEquals(1, users.insertIgnore(id, 900001))
        assertEquals(id, users.findIdByIsu(900001))
        assertEquals(1, users.insertSettingsIgnore(id))
        val createdAt = users.findByIsu(900001)!!.createdAt
        em.clear()

        assertEquals(0, users.insertIgnore(id, 900001))
        val anotherId = UUID.randomUUID()
        assertEquals(0, users.insertIgnore(anotherId, 900001))
        assertEquals(id, users.findIdByIsu(900001))
        em.clear()

        val existing = assertNotNull(users.findByIsu(900001))
        assertEquals(id, existing.id)
        assertEquals(id, existing.settings.userId)
        assertEquals(createdAt, existing.createdAt)
        assertEquals(SharingVisibility.FRIENDS, existing.settings.scheduleVisibility)
        assertEquals(1L, users.count())
        assertFalse(users.existsById(anotherId))
    }

    @Test
    fun `booking inserts ignore duplicate and unknown lessons while preserving original creation time`() {
        val owner = user(900001)
        val other = user(900002)
        val lesson = lesson(100, transactionTime().plusHours(1))
        val original = em.persistAndFlush(UserSportLesson(
            user = owner,
            lesson = lesson,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        ))
        em.clear()

        val now = transactionTime().toInstant()
        bookings.insertLessonsIgnoreDuplicates(owner.id, listOf(lesson.id, lesson.id, 99999), now)
        bookings.insertLessonsIgnoreDuplicates(other.id, listOf(lesson.id), now)
        bookings.insertLessonsIgnoreDuplicates(owner.id, emptyList(), now)
        em.clear()

        val all = bookings.findAll()
        assertEquals(2, all.size)
        val unchanged = all.single { it.user.id == owner.id }
        assertEquals(original.id, unchanged.id)
        assertEquals(original.createdAt, unchanged.createdAt)
        assertEquals(lesson.id, all.single { it.user.id == other.id }.lesson.id)
    }

    @Test
    fun `booking sync deletes only missing future lessons of the requested user`() {
        val owner = user(900001)
        val other = user(900002)
        val now = transactionTime()
        val past = lesson(100, now.minusHours(2))
        val underway = lesson(101, now.minusMinutes(30))
        val startingNow = lesson(102, now)
        val retained = lesson(103, now.plusHours(1))
        val removed = lesson(104, now.plusHours(2))
        val ownerLessons = listOf(past, underway, startingNow, retained, removed)
        ownerLessons.forEach { em.persist(UserSportLesson(user = owner, lesson = it)) }
        em.persistAndFlush(UserSportLesson(user = other, lesson = removed))
        em.clear()

        bookings.deleteMissingFutureLessons(owner.id, listOf(retained.id), now.toInstant())
        em.clear()

        val afterSync = bookings.findAll()
        assertEquals(
            setOf(past.id, underway.id, startingNow.id, retained.id),
            afterSync.filter { it.user.id == owner.id }.map { it.lesson.id }.toSet(),
        )
        assertEquals(listOf(removed.id), afterSync.filter { it.user.id == other.id }.map { it.lesson.id })

        // An empty confirmed sync uses this sentinel to avoid NOT IN (NULL), which deletes nothing.
        bookings.deleteMissingFutureLessons(owner.id, listOf(-1L), now.toInstant())
        bookings.insertLessonsIgnoreDuplicates(owner.id, emptyList(), now.toInstant())
        em.clear()

        val afterEmptySync = bookings.findAll()
        assertEquals(
            setOf(past.id, underway.id, startingNow.id),
            afterEmptySync.filter { it.user.id == owner.id }.map { it.lesson.id }.toSet(),
        )
        assertEquals(listOf(removed.id), afterEmptySync.filter { it.user.id == other.id }.map { it.lesson.id })
    }

    @Test
    fun `schedule sync and native reads preserve owner date bounds ordering and pair participants`() {
        val firstDay = LocalDate.parse("2026-09-08")
        val nextDay = firstDay.plusDays(1)
        val morning = LocalTime.parse("09:00")
        val afternoon = LocalTime.parse("13:00")
        val lateFirstDay = academicLesson(1, 900001, firstDay, afternoon)
        val earlyNextDay = academicLesson(2, 900001, nextDay, morning)
        val earlyFirstDay = academicLesson(3, 900001, firstDay, morning)
        val before = academicLesson(4, 900001, firstDay.minusDays(1), afternoon)
        val after = academicLesson(5, 900001, nextDay.plusDays(1), morning)
        val otherOwner = academicLesson(3, 900002, firstDay, morning)
        val otherPair = academicLesson(6, 900003, firstDay, morning)
        listOf(900001, 900002, 900003).forEach { user(it) }
        em.flush()
        lessonService.syncLessons(900001, before.date, after.date,
            listOf(lateFirstDay, earlyNextDay, earlyFirstDay, before, after))
        lessonService.syncLessons(900002, firstDay, firstDay, listOf(otherOwner))
        lessonService.syncLessons(900003, firstDay, firstDay, listOf(otherPair))
        em.clear()

        val actual = lessons.findAllByIsuAndDates(900001, firstDay, nextDay)
        assertEquals(listOf(earlyFirstDay.id, lateFirstDay.id, earlyNextDay.id), actual.map { it.id })
        assertEquals(listOf(morning, afternoon, morning), actual.map { it.start })
        assertEquals(listOf(morning.plusHours(1), afternoon.plusHours(1), morning.plusHours(1)), actual.map { it.end })
        assertEquals(setOf(900001, 900002), lessons.findAllUsersByPairId(3).toSet())
        assertTrue(lessons.findAllUsersByPairId(99999).isEmpty())
        assertTrue(lessons.findAllByIsuAndDates(900004, firstDay, nextDay).isEmpty())

        lessonService.syncLessons(900001, firstDay, nextDay, listOf(earlyFirstDay))
        em.clear()
        assertEquals(
            setOf(earlyFirstDay.id, before.id, after.id, otherOwner.id, otherPair.id),
            lessons.findAll().map { it.id }.toSet(),
        )
        lessonService.syncLessons(900001, firstDay, nextDay, emptyList())
        em.clear()
        assertEquals(setOf(before.id, after.id, otherOwner.id, otherPair.id), lessons.findAll().map { it.id }.toSet())
    }

    @Test
    fun `schedule batch upsert preserves row identity and updates date all metadata and nullable fields`() {
        val day = LocalDate.parse("2026-09-08")
        val start = LocalTime.parse("09:00")
        val original = academicLesson(1, 900001, day, start, UUID.randomUUID())
        val otherOwner = academicLesson(1, 900002, day, start)
        user(900001)
        user(900002)
        em.flush()
        lessonService.syncLessons(900001, day, day, listOf(original))
        lessonService.syncLessons(900002, day, day, listOf(otherOwner))
        lessonService.syncLessons(900001, day.plusDays(1), day.plusDays(1), emptyList())
        val replacement = original.toDto().copy(
            date = day.plusDays(1),
            start = LocalTime.parse("14:00"),
            end = LocalTime.parse("15:30"),
            subjectId = 2,
            subjectName = "Updated subject",
            teacherIsu = 300001,
            teacherFio = "Synthetic teacher",
            type = "Практика",
            typeId = 3,
            groupName = "M3200",
            flowId = 2,
            flowTypeId = 2,
            note = "Updated note",
            room = "101",
            building = "Synthetic building",
            buildingId = 3,
            mainBuildingId = 4,
            format = "Дистанционный",
            formatId = 3,
        ).toEntity(original.userIsu)
        assertTrue(original.id != replacement.id)

        lessonService.syncLessons(900001, day, day.plusDays(1), listOf(replacement))
        lessonService.syncLessons(900001, day, day.plusDays(1), listOf(replacement))
        em.clear()

        assertEquals(2L, lessons.count())
        val saved = lessons.findById(original.id).orElseThrow()
        assertEquals(replacement.toDto(), saved.toDto())
        assertFalse(lessons.existsById(replacement.id))
        assertEquals(otherOwner.toDto(), lessons.findById(otherOwner.id).orElseThrow().toDto())
        assertTrue(lessons.findAllByIsuAndDates(original.userIsu, day, day).isEmpty())
        assertEquals(listOf(original.id), lessons.findAllByIsuAndDates(original.userIsu, day.plusDays(1), day.plusDays(1)).map { it.id })

        lessonService.syncLessons(900001, day, day.plusDays(1), listOf(original))
        em.clear()
        assertEquals(original.toDto(), lessons.findById(original.id).orElseThrow().toDto())
        assertEquals(2L, lessons.count())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `failed sync rolls back deletion and partial updates in an independent transaction`() {
        val isu = 980001
        val day = LocalDate.parse("2026-09-08")
        val start = LocalTime.parse("09:00")
        val removed = academicLesson(1, isu, day, start)
        val retained = academicLesson(2, isu, day, start.plusHours(2))
        try {
            val ownerId = UUID.randomUUID()
            users.insertIgnore(ownerId, isu)
            users.insertSettingsIgnore(ownerId)
            // No test transaction wraps these calls: only the production service can make sync atomic.
            lessonService.syncLessons(isu, day, day, listOf(removed, retained))
            val changed = retained.toDto().copy(subjectName = "Must roll back").toEntity(isu)
            val invalid = academicLesson(3, isu, day, start.plusHours(4), retained.id)

            assertFailsWith<DataIntegrityViolationException> {
                lessonService.syncLessons(isu, day, day, listOf(changed, invalid))
            }

            assertEquals(
                listOf(removed.toDto(), retained.toDto()),
                lessons.findAllByIsuAndDates(isu, day, day).map { it.toDto() },
            )
        } finally {
            jdbc.update("DELETE FROM lessons WHERE user_isu = ?", isu)
            jdbc.update("DELETE FROM users WHERE isu = ?", isu)
        }
    }

    private fun academicLesson(
        pairId: Long,
        userIsu: Int,
        date: LocalDate,
        start: LocalTime,
        id: UUID = UUID.nameUUIDFromBytes(("$pairId-$userIsu").toByteArray()),
    ): LessonEntity = LessonEntity(
        id = id,
        userIsu = userIsu,
        date = date,
        pairId = pairId,
        subjectId = 1,
        subjectName = "Synthetic subject",
        teacherIsu = null,
        teacherFio = null,
        start = start,
        end = start.plusHours(1),
        type = "Лекция",
        typeId = 1,
        groupName = "M3100",
        flowId = 1,
        flowTypeId = 1,
        note = null,
        room = null,
        building = null,
        buildingId = null,
        mainBuildingId = null,
        format = "Очно",
        formatId = 1,
    )

    private fun user(isu: Int): User = em.persist(User(isu = isu, pictureUrl = null, name = "Synthetic user").apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun transactionTime(): OffsetDateTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP") { rs, _ ->
        rs.getObject(1, OffsetDateTime::class.java)
    }!!

    private fun lesson(id: Long, start: OffsetDateTime): SportLesson = em.persist(SportLesson(
        id = id,
        section = em.find(SportSection::class.java, 1L)
            ?: em.persist(SportSection(id = 1, name = "Section")),
        sectionLevel = 1,
        lessonLevel = 1,
        typeId = 1,
        sectionName = "Section",
        timeSlot = em.find(SportTimeSlot::class.java, 1L)
            ?: em.persist(SportTimeSlot(id = 1, timeStart = "10:00", timeEnd = "11:00")),
        buildingId = 1L,
        teacher = em.find(SportTeacher::class.java, 1L)
            ?: em.persist(SportTeacher(isu = 1, name = "Teacher")),
        roomId = 1,
        roomName = "Room",
        start = start,
        end = start.plusHours(1),
        lastSeenAt = transactionTime().toInstant(),
    ))
}
