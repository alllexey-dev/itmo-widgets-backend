package dev.alllexey.itmowidgets.backend.feature.schedule.service

import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService.Companion.MAX_LABEL
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService.Companion.MAX_LESSONS
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService.Companion.MAX_NAME
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService.Companion.MAX_NOTE
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The same beans as ScheduleFlowMembershipTest, so the classes share one Spring context.
@Import(LessonService::class, ScheduleFlowMembership::class)
class LessonSyncLimitsTest @Autowired constructor(
    private val lessons: LessonService,
    private val em: TestEntityManager,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {

    @Test
    fun `a snapshot at the limits is stored and one past them is refused whole`() {
        val student = em.persistUser(961101)
        val atLimits = (1L..MAX_LESSONS).map { lesson(it, student.isu) }.toMutableList()
        atLimits[0] = lesson(1, student.isu, name = "Ф".repeat(MAX_NAME), note = "N".repeat(MAX_NOTE), label = "Т".repeat(MAX_LABEL))
        lessons.syncLessons(student.isu, DAY, DAY, atLimits)
        assertEquals(MAX_LESSONS, stored(student.isu))

        val tooLong = listOf(
            lesson(1, student.isu, name = "Ф".repeat(MAX_NAME + 1)),
            lesson(1, student.isu, groupName = "P".repeat(MAX_NAME + 1)),
            lesson(1, student.isu, note = "N".repeat(MAX_NOTE + 1)),
            lesson(1, student.isu, label = "Т".repeat(MAX_LABEL + 1)),
        )
        for (lesson in tooLong) {
            assertFailsWith<InvalidRequestDataException> { lessons.syncLessons(student.isu, DAY, DAY, listOf(lesson)) }
        }
        val tooMany = (1L..MAX_LESSONS + 1).map { lesson(it, student.isu) }
        assertFailsWith<InvalidRequestDataException> { lessons.syncLessons(student.isu, DAY, DAY, tooMany) }
        assertEquals(MAX_LESSONS, stored(student.isu), "A refused snapshot changes nothing")
    }

    private fun stored(isu: Int): Int = jdbc.queryForObject("SELECT count(*) FROM lessons WHERE user_isu = ?", Int::class.java, isu)!!

    private fun lesson(
        pairId: Long,
        userIsu: Int,
        name: String = "Synthetic subject",
        groupName: String = "P3119",
        note: String? = null,
        label: String = "Практика",
    ) = LessonEntity(
        userIsu = userIsu, date = DAY, pairId = pairId, subjectId = 42, subjectName = name, teacherIsu = null,
        teacherFio = name, start = LocalTime.parse("10:00"), end = LocalTime.parse("11:30"), type = label, typeId = 3,
        groupName = groupName, flowId = 7002, flowTypeId = 2, note = note, room = name, building = name, buildingId = null,
        mainBuildingId = null, format = label, formatId = 1,
    )

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-09-21")
    }
}
