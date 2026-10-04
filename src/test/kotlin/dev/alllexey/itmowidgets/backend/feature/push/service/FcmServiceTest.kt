package dev.alllexey.itmowidgets.backend.feature.push.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmJsonWrapper
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import dev.alllexey.itmowidgets.backend.feature.push.web.SportAutoSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEvent
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEventPayload
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportLessonDto
import dev.alllexey.itmowidgets.backend.feature.users.web.GroupData
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FcmServiceTest {
    private val springMapper: JsonMapper =
        AnnotationConfigApplicationContext(JacksonAutoConfiguration::class.java).use { it.getBean(JsonMapper::class.java) }
    private val firebase = mock(FirebaseMessaging::class.java)
    private val service = FcmService(springMapper, firebase)

    private val user =
        UserData(100002, "Synthetic actor", null, listOf(GroupData("K3240", 2, "FITIP")), UserCapabilities(false, true, true))
    private val occurredAt = OffsetDateTime.parse("2026-09-15T10:00:00+03:00")

    @Test
    fun `FCM data binds the recipient and keeps the type and payload envelope`() {
        val actor = user.copy(pictureUrl = "https://example.org/avatars/100002.jpg")
        val payload = FriendshipEventPayload(FriendshipEvent.REQUEST_ACCEPTED, actor, occurredAt)

        val data = send(payload)

        assertEquals(setOf("data", "recipient_isu"), data.keySet())
        assertEquals("100001", data["recipient_isu"].asString)
        val wrapper = jacksonMapperBuilder().build().readValue<FcmJsonWrapper>(data["data"].asString)
        val decoded = wrapper.payload
        assertEquals(payload.getType(), wrapper.type)
        assertEquals(setOf("event", "user", "occurredAt"), decoded.propertyNames().toSet())
        assertEquals(payload.user.isu, decoded["user"]["isu"].asInt())
        assertFalse(decoded["user"]["capabilities"]["canViewSchedule"].asBoolean())
        assertTrue(decoded["user"]["capabilities"]["canViewSport"].asBoolean())
        assertEquals(payload.occurredAt, OffsetDateTime.parse(decoded["occurredAt"].asString()))
        assertEquals(payload.event.name, decoded["event"].asString())
    }

    @Test
    fun `FCM data omits null fields while the shared HTTP mapper keeps writing them`() {
        val payload = FriendshipEventPayload(FriendshipEvent.REQUEST_RECEIVED, user, occurredAt)

        val decoded = jacksonMapperBuilder().build().readTree(send(payload)["data"].asString)["payload"]["user"]

        assertFalse(decoded.has("pictureUrl"))
        assertTrue(springMapper.valueToTree<JsonNode>(user)["pictureUrl"].isNull)
    }

    @Test
    fun `one lesson per message keeps FCM data far below the 4096 byte limit`() {
        val lesson = SportLessonDto(
            id = 9_000_000_001,
            sectionId = 41,
            sectionName = "Плавание".repeat(4),
            sectionLevel = 2,
            level = 4,
            typeId = 8,
            buildingId = 13,
            roomName = "Бассейн спортивного комплекса".repeat(2),
            start = OffsetDateTime.parse("2026-10-07T10:00:00+03:00"),
            end = OffsetDateTime.parse("2026-10-07T11:30:00+03:00"),
            timeSlotId = 3,
            teacherIsu = 200001,
            teacherFio = "Тренерова Тренера Тренеровна",
        )
        val longUser =
            user.copy(name = "Синтетический Пользователь Длинноимённый", pictureUrl = "https://example.org/avatars/100002.jpg")

        val payloads = listOf(
            SportAutoSignLessonsPayload(listOf(lesson)),
            FriendshipEventPayload(FriendshipEvent.REQUEST_RECEIVED, longUser, occurredAt),
        )
        for (payload in payloads) {
            clearInvocations(firebase)
            val bytes = send(payload).entrySet().sumOf { (key, value) -> key.utf8Size() + value.asString.utf8Size() }
            assertTrue(bytes < 1024, "${payload.getType()} data takes $bytes bytes")
        }
    }

    @Test
    fun `missing recipient cannot produce an unsafe unscoped message`() {
        for (isu in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                service.sendDataMessage("synthetic-fcm-token", FcmTypedWrapper("synthetic", "payload"), isu)
            }
        }
        verifyNoInteractions(firebase)
    }

    private fun send(payload: FcmPayload): JsonObject {
        service.sendDataMessage("synthetic-fcm-token", FcmTypedWrapper(payload.getType(), payload), 100001)
        val messages = ArgumentCaptor.forClass(Message::class.java)
        verify(firebase).send(messages.capture())
        return Gson().toJsonTree(messages.value).asJsonObject["data"].asJsonObject
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
}
