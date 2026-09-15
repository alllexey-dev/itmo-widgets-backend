package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.services.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Import(FriendService::class, UserService::class, UserRegistrationService::class,
    UserProfileService::class, UserPrivacyService::class,
    FriendshipPersistenceTest.TimeConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FriendshipPersistenceTest @Autowired constructor(
    private val friends: FriendService,
    private val profiles: UserProfileService,
    private val registration: UserRegistrationService,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var itmoJwtVerifier: ItmoJwtVerifier
    @MockitoBean private lateinit var groupService: GroupService
    private val isus = mutableListOf<Int>()

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC)
    }

    @AfterEach
    fun cleanSyntheticRows() {
        for (isu in isus) {
            jdbc.update("DELETE FROM friendships WHERE requester_id IN (SELECT id FROM users WHERE isu = ?) OR addressee_id IN (SELECT id FROM users WHERE isu = ?)", isu, isu)
            jdbc.update("DELETE FROM users WHERE isu = ?", isu)
        }
    }

    @Test
    fun `request lists and capability revocation persist after every committed action`() {
        val first = owner()
        val second = owner()
        val viewerId = registration.findOrCreateByIsu(first).id
        fun act(action: UserProfileService.Action) = profiles.act(viewerId, second, action)
        assertEquals(RelationshipState.OUTGOING, act(UserProfileService.Action.REQUEST).relationship)
        assertEquals(listOf(second), friends.getOutgoingRequests(first))
        assertEquals(listOf(first), friends.getIncomingRequests(second))
        assertTrue(friends.getFriends(first).isEmpty())
        assertFalse(profiles.profile(viewerId, second).user.capabilities.canViewSport)
        friends.sendRequest(second, first)
        val accepted = profiles.profile(viewerId, second)
        assertEquals(RelationshipState.FRIENDS, accepted.relationship)
        assertTrue(accepted.user.capabilities.canViewSchedule)
        assertEquals(listOf(second), friends.getFriends(first))
        assertEquals(listOf(first), friends.getFriends(second))
        assertTrue(friends.getIncomingRequests(second).isEmpty())
        assertTrue(friends.getOutgoingRequests(first).isEmpty())
        val removed = act(UserProfileService.Action.REMOVE)
        assertEquals(RelationshipState.NONE, removed.relationship)
        assertFalse(removed.user.capabilities.canViewSchedule)
        assertFalse(friends.areFriends(second, first))
        friends.sendRequest(second, first)
        assertEquals(RelationshipState.NONE, act(UserProfileService.Action.REJECT).relationship)
        act(UserProfileService.Action.REQUEST)
        assertEquals(RelationshipState.NONE, act(UserProfileService.Action.CANCEL).relationship)
    }

    @Test
    fun `simultaneous first requests converge to one pending row and crossed requests to one accepted row`() {
        for (crossed in listOf(false, true)) {
            val first = owner()
            val second = owner()
            val executor = Executors.newFixedThreadPool(2)
            val barrier = CyclicBarrier(2)
            try {
                val tasks = listOf(
                    executor.submit { barrier.await(10, TimeUnit.SECONDS); friends.sendRequest(first, second) },
                    executor.submit { barrier.await(10, TimeUnit.SECONDS); friends.sendRequest(if (crossed) second else first, if (crossed) first else second) },
                )
                tasks.forEach { it.get(15, TimeUnit.SECONDS) }
                assertEquals(if (crossed) RelationshipState.FRIENDS else RelationshipState.OUTGOING, friends.relationship(first, second))
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM friendships WHERE requester_id IN (SELECT id FROM users WHERE isu IN (?, ?))", Int::class.java, first, second))
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun owner(): Int = nextIsu.getAndIncrement().also {
        registration.findOrCreateByIsu(it)
        isus.add(it)
    }

    companion object { private val nextIsu = AtomicInteger(880001) }
}
