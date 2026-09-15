package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.springframework.core.task.TaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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
    FriendshipNotificationService::class, FriendshipNotificationPayloadService::class,
    DeviceService::class, DeviceDeliveryStore::class,
    FriendshipPersistenceTest.TimeConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FriendshipPersistenceTest @Autowired constructor(
    private val friends: FriendService,
    private val profiles: UserProfileService,
    private val registration: UserRegistrationService,
    private val jdbc: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
    private val notificationExecutor: QueuedNotifications,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var itmoJwtVerifier: ItmoJwtVerifier
    @MockitoBean private lateinit var groupService: GroupService
    @MockitoBean private lateinit var fcm: FcmService
    private val isus = mutableListOf<Int>()

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun friendshipNotificationExecutor() = QueuedNotifications()
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC)
    }

    class QueuedNotifications : TaskExecutor {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun drain() {
            val executor = Executors.newSingleThreadExecutor()
            try {
                while (true) executor.submit(tasks.poll() ?: break).get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @AfterEach
    fun cleanSyntheticRows() {
        notificationExecutor.tasks.clear()
        for (isu in isus) {
            jdbc.update("DELETE FROM devices WHERE user_id IN (SELECT id FROM users WHERE isu = ?)", isu)
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

    @Test
    fun `notifications are enqueued only after commit and FCM never holds a transaction`() {
        val first = owner()
        val second = owner()
        val senderId = registration.findOrCreateByIsu(first).id
        val recipientId = registration.findOrCreateByIsu(second).id
        jdbc.update("INSERT INTO devices(id, user_id, fcm_token, device_name, last_login) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), recipientId, "synthetic-friendship-token", "Synthetic device", OffsetDateTime.now())
        val tx = TransactionTemplate(transactionManager)
        assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                profiles.act(senderId, second, UserProfileService.Action.REQUEST)
                assertTrue(notificationExecutor.tasks.isEmpty())
                error("Synthetic rollback")
            }
        }
        assertTrue(notificationExecutor.tasks.isEmpty())
        verifyNoInteractions(fcm)
        assertEquals(RelationshipState.NONE, friends.relationship(first, second))
        tx.executeWithoutResult {
            profiles.act(senderId, second, UserProfileService.Action.REQUEST)
            assertTrue(notificationExecutor.tasks.isEmpty())
        }
        assertEquals(1, notificationExecutor.tasks.size)
        verifyNoInteractions(fcm)
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(RelationshipState.INCOMING, friends.relationship(second, first))
            null
        }.`when`(fcm).sendDataMessage(anyString(), any<FcmTypedWrapper<Any?>>(), org.mockito.ArgumentMatchers.anyInt())
        notificationExecutor.drain()
        verify(fcm).sendDataMessage(anyString(), any<FcmTypedWrapper<Any?>>(), org.mockito.ArgumentMatchers.anyInt())
    }

    @Test
    fun `recipient without devices and cancelled notification do not break committed actions`() {
        val first = owner()
        val second = owner()
        val senderId = registration.findOrCreateByIsu(first).id
        profiles.act(senderId, second, UserProfileService.Action.REQUEST)
        notificationExecutor.drain()
        assertEquals(RelationshipState.OUTGOING, friends.relationship(first, second))
        profiles.act(senderId, second, UserProfileService.Action.CANCEL)
        profiles.act(senderId, second, UserProfileService.Action.REQUEST)
        profiles.act(senderId, second, UserProfileService.Action.CANCEL)
        notificationExecutor.drain()
        verifyNoInteractions(fcm)
    }

    private fun owner(): Int = nextIsu.getAndIncrement().also {
        registration.findOrCreateByIsu(it)
        isus.add(it)
    }

    companion object { private val nextIsu = AtomicInteger(880001) }
}
