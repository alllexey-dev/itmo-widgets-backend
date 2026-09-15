package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.FriendshipRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

class FriendServiceTest {
    private val repository = mock(FriendshipRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val userService = mock(UserService::class.java)
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val service = FriendService(repository, users, userService, Clock.fixed(now, ZoneOffset.UTC))
    private val first = User(isu = 100001, name = "Synthetic first", pictureUrl = null)
    private val second = User(isu = 100002, name = "Synthetic second", pictureUrl = null)
    private var stored: FriendshipEntity? = null

    @BeforeEach
    fun repositoryFixture() {
        for (person in listOf(first, second)) {
            `when`(users.lockByIsu(person.isu)).thenReturn(person.id)
            `when`(userService.findUserByIsu(person.isu)).thenReturn(person)
        }
        for ((a, b) in listOf(first to second, second to first)) {
            `when`(repository.findBetween(a.isu, b.isu)).thenAnswer { stored }
        }
        `when`(repository.save(any(FriendshipEntity::class.java))).thenAnswer {
            it.getArgument<FriendshipEntity>(0).also { row -> stored = row }
        }
        doAnswer { stored = null; null }.`when`(repository).delete(any(FriendshipEntity::class.java))
    }

    @ParameterizedTest
    @CsvSource(
        "NONE,REQUEST,OUTGOING", "OUTGOING,REQUEST,OUTGOING", "INCOMING,REQUEST,FRIENDS", "FRIENDS,REQUEST,FRIENDS",
        "NONE,ACCEPT,CONFLICT", "OUTGOING,ACCEPT,CONFLICT", "INCOMING,ACCEPT,FRIENDS", "FRIENDS,ACCEPT,FRIENDS",
        "NONE,REJECT,NONE", "OUTGOING,REJECT,CONFLICT", "INCOMING,REJECT,NONE", "FRIENDS,REJECT,CONFLICT",
        "NONE,CANCEL,NONE", "OUTGOING,CANCEL,NONE", "INCOMING,CANCEL,CONFLICT", "FRIENDS,CANCEL,CONFLICT",
        "NONE,REMOVE,NONE", "OUTGOING,REMOVE,CONFLICT", "INCOMING,REMOVE,CONFLICT", "FRIENDS,REMOVE,NONE",
    )
    fun `every action respects state and request direction`(state: RelationshipState, action: String, expected: String) {
        stored = when (state) {
            RelationshipState.NONE -> null
            RelationshipState.INCOMING -> FriendshipEntity(requester = second, addressee = first, createdAt = now.minusSeconds(60))
            else -> FriendshipEntity(requester = first, addressee = second, createdAt = now.minusSeconds(60)).apply {
                if (state == RelationshipState.FRIENDS) {
                    status = FriendshipEntity.Status.ACCEPTED
                    respondedAt = now.minusSeconds(30)
                }
            }
        }
        val old = stored
        val oldResponse = stored?.respondedAt
        val operation = operations().getValue(action)
        if (expected == "CONFLICT") {
            assertFailsWith<BusinessRuleException> { operation(first.isu, second.isu) }
            assertEquals(state, service.relationship(first.isu, second.isu))
            assertEquals(oldResponse, stored?.respondedAt)
            verify(repository, never()).delete(any(FriendshipEntity::class.java))
            return
        }
        operation(first.isu, second.isu)
        assertEquals(RelationshipState.valueOf(expected), service.relationship(first.isu, second.isu))
        if (expected == "FRIENDS") {
            assertEquals(old?.id, stored?.id)
            assertEquals(if (state == RelationshipState.FRIENDS) oldResponse else now, stored?.respondedAt)
            assertTrue(service.areFriends(first.isu, second.isu))
            assertTrue(service.areFriends(second.isu, first.isu))
        }
        val after = stored?.let { Triple(it.id, it.createdAt, it.respondedAt) }
        operation(first.isu, second.isu)
        assertEquals(after, stored?.let { Triple(it.id, it.createdAt, it.respondedAt) })
    }

    @Test
    fun `new request has deterministic timestamp and symmetric viewer states`() {
        service.sendRequest(first.isu, second.isu)
        assertEquals(now, stored?.createdAt)
        assertNull(stored?.respondedAt)
        assertEquals(RelationshipState.OUTGOING, service.relationship(first.isu, second.isu))
        assertEquals(RelationshipState.INCOMING, service.relationship(second.isu, first.isu))
        assertFalse(service.areFriends(first.isu, second.isu))
        assertEquals(RelationshipState.NONE, service.relationship(first.isu, first.isu))
        assertFalse(service.areFriends(first.isu, first.isu))
    }

    @Test
    fun `both former friends can request again after either side removes friendship`() {
        service.sendRequest(first.isu, second.isu)
        service.sendRequest(second.isu, first.isu)
        val originalId = stored?.id
        service.removeFriend(second.isu, first.isu)
        assertFalse(service.areFriends(first.isu, second.isu))
        service.sendRequest(second.isu, first.isu)
        assertEquals(RelationshipState.INCOMING, service.relationship(first.isu, second.isu))
        assertNotEquals(originalId, stored?.id)
        service.rejectRequest(first.isu, second.isu)
        service.sendRequest(first.isu, second.isu)
        assertEquals(RelationshipState.OUTGOING, service.relationship(first.isu, second.isu))
    }

    @Test
    fun `every mutation rejects self and invalid ISUs before persistence`() {
        for (operation in operations().values) {
            for ((a, b) in listOf(first.isu to first.isu, 0 to second.isu, first.isu to -1)) {
                assertFailsWith<InvalidRequestDataException> { operation(a, b) }
            }
        }
        verifyNoInteractions(repository, users, userService)
    }

    @Test
    fun `missing user fails without creating an account or mutating friendships`() {
        for (operation in operations().values) {
            assertFailsWith<NotFoundException> { operation(first.isu, 999999) }
        }
        verifyNoInteractions(repository, userService)
    }

    @Test
    fun `reverse requests acquire existing users in the same order before reading the pair`() {
        service.sendRequest(second.isu, first.isu)
        val order = inOrder(users, repository)
        order.verify(users).lockByIsu(first.isu)
        order.verify(users).lockByIsu(second.isu)
        order.verify(repository).findBetween(second.isu, first.isu)
    }

    private fun operations(): Map<String, (Int, Int) -> Unit> = mapOf(
        "REQUEST" to service::sendRequest, "ACCEPT" to service::acceptRequest,
        "REJECT" to service::rejectRequest, "CANCEL" to service::cancelRequest, "REMOVE" to service::removeFriend,
    )
}
