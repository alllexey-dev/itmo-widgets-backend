package dev.alllexey.itmowidgets.backend.services

import api.myitmo.MyItmo
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import dev.alllexey.itmowidgets.backend.dto.FriendshipEvent
import dev.alllexey.itmowidgets.backend.dto.FriendshipEventPayload
import dev.alllexey.itmowidgets.backend.dto.UserCapabilities
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.core.ItmoWidgetsImpl
import dev.alllexey.itmowidgets.core.model.fcm.FcmJsonWrapper
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import dev.alllexey.itmowidgets.core.model.fcm.impl.FriendshipEventPayload as CoreFriendshipEventPayload
import java.time.OffsetDateTime
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class FcmServiceTest {
    private val myItmo = MyItmo()
    private val source = mock(MyItmoService::class.java).also { `when`(it.myItmo).thenReturn(myItmo) }
    private val firebase = mock(FirebaseMessaging::class.java)
    private val service = FcmService(source, firebase)

    @Test
    fun `FCM data binds the recipient while the existing envelope decodes through Core`() {
        val payload = FriendshipEventPayload(FriendshipEvent.REQUEST_ACCEPTED,
            UserData(100002, "Synthetic actor", null, emptyList(), UserCapabilities(false, true)),
            OffsetDateTime.parse("2026-09-15T10:00:00+03:00"))
        service.sendDataMessage("synthetic-fcm-token", FcmTypedWrapper(payload.getType(), payload), 100001)
        val messages = ArgumentCaptor.forClass(Message::class.java)
        verify(firebase).send(messages.capture())
        val data = myItmo.gson.toJsonTree(messages.value).asJsonObject["data"].asJsonObject
        assertEquals(setOf("data", "recipient_isu"), data.keySet())
        assertEquals("100001", data["recipient_isu"].asString)
        val gson = ItmoWidgetsImpl(myItmo).gson
        val wrapper = gson.fromJson(data["data"].asString, FcmJsonWrapper::class.java)
        val decoded = gson.fromJson(wrapper.payload, CoreFriendshipEventPayload::class.java)
        assertEquals(payload.getType(), wrapper.type)
        assertEquals(payload.user.isu, decoded.user.isu)
        assertFalse(decoded.user.capabilities.canViewSchedule)
        assertTrue(decoded.user.capabilities.canViewSport)
        assertEquals(payload.occurredAt, decoded.occurredAt)
        assertEquals(payload.event.name, decoded.event.name)
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
}
