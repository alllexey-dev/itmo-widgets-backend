package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.core.model.fcm.FcmPayload
import java.time.OffsetDateTime
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.*

class FriendshipNotificationServiceTest {
    private val users = mock(UserService::class.java)
    private val friends = mock(FriendService::class.java)
    private val devices = mock(DeviceService::class.java)
    private val payloads = FriendshipNotificationPayloadService(users, UserPrivacyService(friends), friends)
    private val service = FriendshipNotificationService(payloads, devices)
    private val recipient = User(isu = 100001, name = "Recipient", pictureUrl = null)
    private val actor = User(isu = 100002, name = "Actor", pictureUrl = null)
    private val now = OffsetDateTime.parse("2026-09-15T10:00:00+03:00")

    @ParameterizedTest
    @EnumSource(FriendshipEvent::class)
    fun `identity uses recipient capabilities and event time`(event: FriendshipEvent) {
        val intent = fixture(event)
        actor.settings = UserSettingsEntity(actor)
        actor.settings.scheduleVisibility = SharingVisibility.ALL
        actor.settings.sportVisibility = SharingVisibility.NOBODY
        val payload = assertNotNull(payloads.currentPayload(intent))
        assertEquals(actor.isu, payload.user.isu)
        assertEquals(actor.name, payload.user.name)
        assertTrue(payload.user.capabilities.canViewSchedule)
        assertFalse(payload.user.capabilities.canViewSport)
        assertEquals(now, payload.occurredAt)
        assertEquals(event, payload.event)
        assertEquals("FRIENDSHIP_EVENT_PAYLOAD", payload.getType())
        service.deliver(intent)
        verify(devices).sendDataMessageToUser(recipient.id, payload as FcmPayload)
    }

    @Test
    fun `obsolete events are suppressed and delivery errors never escape`() {
        val intent = fixture(FriendshipEvent.REQUEST_RECEIVED)
        `when`(friends.relationship(recipient.isu, actor.isu)).thenReturn(RelationshipState.NONE)
        service.deliver(intent)
        verifyNoInteractions(devices)
        `when`(users.findUserById(recipient.id)).thenThrow(IllegalStateException("Synthetic failure"))
        service.deliver(intent)
        verifyNoInteractions(devices)
    }

    private fun fixture(event: FriendshipEvent): FriendshipNotificationIntent {
        `when`(users.findUserById(recipient.id)).thenReturn(recipient)
        `when`(users.findUserByIsu(actor.isu)).thenReturn(actor)
        `when`(friends.relationship(recipient.isu, actor.isu)).thenReturn(
            if (event == FriendshipEvent.REQUEST_RECEIVED) RelationshipState.INCOMING else RelationshipState.FRIENDS)
        return FriendshipNotificationIntent(recipient.id, actor.isu, event, now)
    }
}
