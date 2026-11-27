package dev.alllexey.itmowidgets.backend.feature.connections.model

import dev.alllexey.itmowidgets.backend.feature.push.web.SportAutoSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.SportFreeSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEvent
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEventPayload
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals

class ConnectionScopeTest {
    @Test
    fun `scopes name the push types they receive`() {
        val friendship = FriendshipEventPayload(
            FriendshipEvent.REQUEST_RECEIVED,
            UserData(1, "", null, emptyList(), UserCapabilities(false, false, false)),
            OffsetDateTime.parse("2026-10-12T12:00:00+03:00"),
        )
        assertEquals(
            setOf(SportFreeSignLessonsPayload(emptyList()).getType(), SportAutoSignLessonsPayload(emptyList()).getType()),
            ConnectionScope.SPORT.eventTypes,
        )
        assertEquals(setOf(friendship.getType()), ConnectionScope.FRIENDS_READ.eventTypes)
    }

    @Test
    fun `the stored form keeps declaration order and ignores unknown names`() {
        assertEquals("sport,schedule:write", ConnectionScope.format(setOf(ConnectionScope.SCHEDULE_WRITE, ConnectionScope.SPORT)))
        assertEquals(setOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ), ConnectionScope.parse("sport,friends:read,unknown"))
    }
}
