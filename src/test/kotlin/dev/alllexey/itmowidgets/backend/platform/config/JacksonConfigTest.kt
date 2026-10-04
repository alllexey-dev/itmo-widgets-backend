package dev.alllexey.itmowidgets.backend.platform.config

import dev.alllexey.itmowidgets.backend.feature.schedule.web.LessonSyncRequest
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.web.UserPrivacySettings
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Spring's request mapper keeps the Jackson 2 reading installed apps were built against (SP-17 samples 08 and 10). */
class JacksonConfigTest {
    private val withJackson = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))

    @Test
    fun `content after the request body is ignored`() {
        withJackson.withUserConfiguration(JacksonConfig::class.java).run { context ->
            val settings = context.getBean(JsonMapper::class.java).readValue(PRIVACY_WITH_TRAILER, UserPrivacySettings::class.java)

            assertEquals(UserPrivacySettings(SharingVisibility.FRIENDS, SharingVisibility.ALL, SharingVisibility.NOBODY), settings)
        }
    }

    @Test
    fun `null for a JVM primitive reads as its default`() {
        withJackson.withUserConfiguration(JacksonConfig::class.java).run { context ->
            val request = context.getBean(JsonMapper::class.java).readValue(LESSON_WITH_NULL_TYPE_ID, LessonSyncRequest::class.java)

            assertEquals(0, request.lessons.single().typeId)
        }
    }

    @Test
    fun `without the customizer Jackson 3 rejects both`() {
        withJackson.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)

            assertFailsWith<MismatchedInputException> { mapper.readValue(PRIVACY_WITH_TRAILER, UserPrivacySettings::class.java) }
            assertFailsWith<MismatchedInputException> { mapper.readValue(LESSON_WITH_NULL_TYPE_ID, LessonSyncRequest::class.java) }
        }
    }

    private companion object {
        const val PRIVACY_WITH_TRAILER =
            """{"scheduleVisibility": "FRIENDS", "sportVisibility": "ALL", "friendsVisibility": "NOBODY"} {}"""

        val LESSON_WITH_NULL_TYPE_ID = """
            {
              "lessons": [{
                "pairId": 3000001, "date": "2026-10-06", "start": "08:20", "end": "09:50",
                "type": "Лекции", "typeId": null, "note": null, "subjectName": "Математический анализ",
                "subjectId": 501, "groupName": "ЛЕК МАТАН 3.1", "flowId": 7001, "flowTypeId": 2,
                "teacherIsu": 200001, "teacherFio": "Преподаватель Тестовый", "room": "1404",
                "building": "Кронверкский пр., д.49", "buildingId": 13, "mainBuildingId": 13,
                "format": "Очно", "formatId": 1
              }],
              "from": "2026-10-06",
              "to": "2026-10-12"
            }
        """.trimIndent()
    }
}
