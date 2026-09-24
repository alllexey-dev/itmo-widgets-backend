package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.repositories.UserSubjectFlowRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import

@Import(LessonService::class, ScheduleFlowMembership::class)
class ScheduleFlowMembershipTest @Autowired constructor(
    private val lessons: LessonService,
    private val membership: FlowMembership,
    private val flows: UserSubjectFlowRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {

    @Test
    fun `schedule sync records lecture and practice flows per subject and period`() {
        val student = user(961001)
        lessons.syncLessons(student.isu, AUTUMN, AUTUMN.plusDays(7), listOf(
            lesson(1, student.isu, AUTUMN, flowId = 7001, typeId = LECTURE, groupName = "P3119, P3120"),
            lesson(2, student.isu, AUTUMN.plusDays(1), flowId = 7002, typeId = PRACTICE),
            lesson(3, student.isu, AUTUMN.plusDays(7), flowId = 7002, typeId = PRACTICE),
            lesson(4, student.isu, AUTUMN, flowId = 8001, typeId = LAB, subjectId = 43),
        ))
        em.clear()

        assertEquals(listOf(SubjectFlow(7001, "P3119, P3120", LECTURE, depth = 1), SubjectFlow(7002, "P3119", PRACTICE, depth = 1)),
            membership.flowsOf(student.id, SUBJECT, "2026-1"))
        assertEquals(listOf(SubjectFlow(8001, "P3119", LAB, depth = 1)), membership.flowsOf(student.id, 43, "2026-1"))
        assertEquals(AUTUMN.plusDays(7), flows.findByUserAndScope(student.id, SUBJECT, "2026-1").single { it.id.flowId == 7002L }.lastSeen)
        assertTrue(membership.flowsOf(student.id, SUBJECT, "2025-2").isEmpty())
    }

    @Test
    fun `lessons of different semesters in one sync land in their own periods`() {
        val student = user(961011)
        val spring = LocalDate.parse("2026-02-10")
        lessons.syncLessons(student.isu, spring, AUTUMN, listOf(
            lesson(1, student.isu, spring, flowId = 6001, typeId = PRACTICE),
            lesson(2, student.isu, AUTUMN, flowId = 7002, typeId = PRACTICE),
        ))
        em.clear()

        assertEquals(listOf(6001L), membership.flowsOf(student.id, SUBJECT, "2025-2").map { it.flowId })
        assertEquals(listOf(7002L), membership.flowsOf(student.id, SUBJECT, "2026-1").map { it.flowId })
    }

    @Test
    fun `a later sync without the lesson keeps the flow and its last seen date`() {
        val student = user(961021)
        lessons.syncLessons(student.isu, AUTUMN, AUTUMN.plusDays(7), listOf(
            lesson(1, student.isu, AUTUMN, flowId = 7001, typeId = LECTURE),
            lesson(2, student.isu, AUTUMN.plusDays(7), flowId = 7002, typeId = PRACTICE),
        ))
        lessons.syncLessons(student.isu, AUTUMN, AUTUMN.plusDays(7), listOf(
            lesson(1, student.isu, AUTUMN, flowId = 7001, typeId = LECTURE),
        ))
        lessons.syncLessons(student.isu, AUTUMN, AUTUMN, emptyList())
        em.clear()

        assertEquals(listOf(7001L, 7002L), membership.flowsOf(student.id, SUBJECT, "2026-1").map { it.flowId })
        assertEquals(AUTUMN.plusDays(7), flows.findByUserAndScope(student.id, SUBJECT, "2026-1").single { it.id.flowId == 7002L }.lastSeen)
        assertTrue(membership.isMember(student.id, SUBJECT, "2026-1", 7002))
    }

    @Test
    fun `nested flow names get their depth from the trailing flow number`() {
        val student = user(961041)
        lessons.syncLessons(student.isu, AUTUMN, AUTUMN, listOf(
            lesson(1, student.isu, AUTUMN, flowId = 7101, typeId = LECTURE, groupName = "ФИЗ ПИИКТ 3"),
            lesson(2, student.isu, AUTUMN, flowId = 7102, typeId = PRACTICE, groupName = "ФИЗ ПИИКТ 3.2"),
            lesson(3, student.isu, AUTUMN, flowId = 7103, typeId = LAB, groupName = "ФИЗ ПИИКТ 3.2.1"),
        ))
        em.clear()

        assertEquals(listOf(SubjectFlow(7101, "ФИЗ ПИИКТ 3", LECTURE, 1), SubjectFlow(7102, "ФИЗ ПИИКТ 3.2", PRACTICE, 2),
            SubjectFlow(7103, "ФИЗ ПИИКТ 3.2.1", LAB, 3)), membership.flowsOf(student.id, SUBJECT, "2026-1"))
        assertEquals(listOf(1, 1, 1, 2, 3), listOf("P3119", "P3119, P3120", "Лекции", "Поток 12.4", "ФИЗ 1.10.2 ")
            .map(SubjectFlow::depthOf))
    }

    @Test
    fun `isMember tells apart two intakes with the same group name by flow id`() {
        val current = user(961031)
        val previous = user(961032)
        lessons.syncLessons(current.isu, AUTUMN, AUTUMN, listOf(lesson(1, current.isu, AUTUMN, flowId = 7002, typeId = PRACTICE)))
        val lastYear = AUTUMN.minusYears(1)
        lessons.syncLessons(previous.isu, lastYear, lastYear, listOf(lesson(1, previous.isu, lastYear, flowId = 5002, typeId = PRACTICE)))
        em.clear()

        val lastYearGroup = membership.flowsOf(previous.id, SUBJECT, "2025-1").single()
        assertEquals("P3119", lastYearGroup.groupName)
        assertFalse(membership.isMember(current.id, SUBJECT, "2026-1", lastYearGroup.flowId))
        assertFalse(membership.isMember(current.id, SUBJECT, "2025-1", lastYearGroup.flowId))
        assertTrue(membership.isMember(previous.id, SUBJECT, "2025-1", lastYearGroup.flowId))
        assertTrue(membership.isMember(current.id, SUBJECT, "2026-1", 7002))
        assertFalse(membership.isMember(current.id, SUBJECT, "2025-1", 7002))
        assertFalse(membership.isMember(current.id, 43, "2026-1", 7002))
    }

    private fun user(isu: Int): User = em.persistAndFlush(User(isu = isu, pictureUrl = null, name = "Synthetic user").apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun lesson(
        pairId: Long,
        userIsu: Int,
        date: LocalDate,
        flowId: Long,
        typeId: Int,
        groupName: String = "P3119",
        subjectId: Long = SUBJECT,
    ) = LessonEntity(
        userIsu = userIsu, date = date, pairId = pairId, subjectId = subjectId, subjectName = "Synthetic subject",
        teacherIsu = null, teacherFio = null, start = LocalTime.parse("10:00"), end = LocalTime.parse("11:30"),
        type = "Synthetic type", typeId = typeId, groupName = groupName, flowId = flowId, flowTypeId = typeId,
        note = null, room = null, building = null, buildingId = null, mainBuildingId = null, format = "Очно", formatId = 1,
    )

    private companion object {
        const val SUBJECT = 42L
        const val LECTURE = 1
        // MyITMO schedule type IDs.
        const val LAB = 2
        const val PRACTICE = 3
        val AUTUMN: LocalDate = LocalDate.parse("2026-09-07")
    }
}
