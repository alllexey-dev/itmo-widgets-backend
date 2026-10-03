package dev.alllexey.itmowidgets.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.gson.Gson
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ISU
import dev.alllexey.itmowidgets.backend.feature.push.service.FcmService
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import dev.alllexey.itmowidgets.backend.feature.push.web.SportAutoSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.SportFreeSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEvent
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEventPayload
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/**
 * The FCM data map exactly as `FcmService` builds it from Spring's `ObjectMapper`, wrapped the way `DeviceService`
 * wraps a payload. The fixture keeps the map's two string values; `data` is stored parsed, so it compares semantically.
 */
class FcmContractTest {
    private val firebase = mock(FirebaseMessaging::class.java)
    private val service = FcmService(
        AnnotationConfigApplicationContext(JacksonAutoConfiguration::class.java).use { it.getBean(ObjectMapper::class.java) },
        firebase,
    )

    private val payloads: Map<String, FcmPayload> = mapOf(
        "FRIENDSHIP_EVENT_PAYLOAD" to FriendshipEventPayload(
            FriendshipEvent.REQUEST_RECEIVED,
            ContractSamples.friendData,
            OffsetDateTime.parse("2026-10-05T12:00:00+03:00"),
        ),
        "SPORT_AUTO_SIGN_LESSONS_PAYLOAD" to SportAutoSignLessonsPayload(ContractSamples.fcmLessons),
        "SPORT_FREE_SIGN_LESSONS_PAYLOAD" to SportFreeSignLessonsPayload(ContractSamples.fcmLessons.take(1)),
    )

    @TestFactory
    fun `FCM data matches its fixtures`(): List<DynamicTest> {
        assertEquals(ContractCatalog.fcm.map { it.id }, payloads.keys.toList())
        return ContractCatalog.fcm.map { fcm ->
            dynamicTest(fcm.id) {
                clearInvocations(firebase)
                val payload = payloads.getValue(fcm.id)
                assertEquals(fcm.id, payload.getType())
                service.sendDataMessage("synthetic-fcm-token", FcmTypedWrapper(payload.getType(), payload), VIEWER_ISU)
                val message = ArgumentCaptor.forClass(Message::class.java)
                verify(firebase).send(message.capture())
                val data = Gson().toJsonTree(message.value).asJsonObject["data"].asJsonObject
                assertEquals(setOf("data", "recipient_isu"), data.keySet())
                val fixture = ContractJson.tree(
                    mapOf(
                        "data" to ContractJson.parse(data["data"].asString),
                        "recipient_isu" to data["recipient_isu"].asString,
                    ),
                )
                ContractFiles.check(fcm.file, fixture)
            }
        }
    }
}
