package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.DeviceDeliveryTarget
import dev.alllexey.itmowidgets.backend.dto.SportNotificationIntent
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.dto.SportQueueKind
import dev.alllexey.itmowidgets.backend.repositories.SportQueuePersistenceTest
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.aop.support.AopUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class SportNotificationDeliveryTest : SportQueuePersistenceTest() {
    private val deliveryDeviceIds = mutableListOf<UUID>()

    @AfterEach
    fun removeDeliveryDevicesBeforeOwnerCleanup() {
        deliveryDeviceIds.forEach { jdbc.update("DELETE FROM devices WHERE id=?", it) }
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `rolled back reservation cannot deliver an escaped intent`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        lateinit var intent: SportNotificationIntent
        assertFailsWith<IllegalStateException> {
            inTransaction {
                intent = prepare(fixture)
                error("Synthetic reservation rollback")
            }
        }

        delivery.deliver(intent)

        verifyNoInteractions(fcm)
        assertEquals("WAITING", status(fixture))
        assertEquals(0, attempts(fixture))
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `FCM runs outside transactions even when delivery has a transactional caller`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val intent = prepare(fixture)
        assertTrue(AopUtils.isAopProxy(delivery))
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(1, attempts(fixture))
            null
        }.`when`(fcm).sendDataMessage(anyString(), any<FcmTypedWrapper<Any?>>())

        inTransaction { delivery.deliver(intent) }

        verify(fcm).sendDataMessage(eq(fixture.token), any<FcmTypedWrapper<Any?>>())
        assertEquals("NOTIFIED", status(fixture))
        assertEquals(1, attempts(fixture))
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `cancellation before the fresh check suppresses delivery`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val intent = prepare(fixture)
        cancel(fixture)

        delivery.deliver(intent)

        verifyNoInteractions(fcm)
        assertCancelled(fixture)
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `satisfaction before the fresh check suppresses delivery`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val intent = prepare(fixture)
        when (kind) {
            SportQueueKind.AUTO -> autos.markEntrySatisfied(fixture.ownerId, fixture.candidate.entryId)
            SportQueueKind.FREE -> frees.markEntrySatisfied(fixture.ownerId, fixture.candidate.entryId)
        }

        delivery.deliver(intent)

        verifyNoInteractions(fcm)
        assertEquals("SATISFIED", status(fixture))
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `cancellation while FCM is in flight survives send completion`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val intent = prepare(fixture)
        val sending = CountDownLatch(1)
        val finishSend = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            sending.countDown()
            assertTrue(finishSend.await(10, TimeUnit.SECONDS))
            null
        }.`when`(fcm).sendDataMessage(anyString(), any<FcmTypedWrapper<Any?>>())
        try {
            val completed = executor.submit { delivery.deliver(intent) }
            assertTrue(sending.await(10, TimeUnit.SECONDS))

            cancel(fixture)
            assertCancelled(fixture)
            finishSend.countDown()
            completed.get(10, TimeUnit.SECONDS)

            verify(fcm).sendDataMessage(eq(fixture.token), any<FcmTypedWrapper<Any?>>())
            assertCancelled(fixture)
            assertEquals(1, attempts(fixture))
        } finally {
            finishSend.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `the last reserved gave up attempt is still deliverable`(kind: SportQueueKind) {
        val fixture = fixture(kind, maxAttempts = 1)
        val intent = prepare(fixture)
        assertEquals("GAVE_UP_NOTIFYING", status(fixture))
        assertEquals(1, intent.attemptNumber)

        delivery.deliver(intent)

        verify(fcm).sendDataMessage(eq(fixture.token), any<FcmTypedWrapper<Any?>>())
        assertEquals("GAVE_UP_NOTIFYING", status(fixture))
        assertEquals(1, attempts(fixture))
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `a newer reserved attempt suppresses an older intent without losing retry delivery`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val first = prepare(fixture)
        clock.advance(Duration.ofMinutes(15))
        val second = prepare(fixture, bindUnresolved = false)
        assertEquals(2, second.attemptNumber)

        delivery.deliver(first)
        verifyNoInteractions(fcm)
        delivery.deliver(second)

        verify(fcm).sendDataMessage(eq(fixture.token), any<FcmTypedWrapper<Any?>>())
        assertEquals(2, attempts(fixture))
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `wrong target and unreserved attempt cannot be delivered`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val intent = prepare(fixture)

        delivery.deliver(intent.copy(lessonId = Long.MAX_VALUE))
        delivery.deliver(intent.copy(attemptNumber = 0))
        delivery.deliver(intent.copy(attemptNumber = intent.attemptNumber + 1))

        verifyNoInteractions(fcm)
        assertEquals(1, attempts(fixture))
    }

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `deadline is checked again at delivery even before queue cleanup`(kind: SportQueueKind) {
        val fixture = fixture(kind)
        val intent = prepare(fixture)
        clock.set(fixture.deadline.minusNanos(1))
        assertTrue(transitions.isIntentCurrent(intent))
        clock.set(fixture.deadline)

        delivery.deliver(intent)

        verifyNoInteractions(fcm)
        assertEquals("NOTIFIED", status(fixture))
        assertEquals(1, attempts(fixture))
    }

    @Test
    fun `force free delivery stays allowed during the lesson but stops at its end`() {
        val fixture = fixture(SportQueueKind.FREE, force = true)
        val intent = prepare(fixture)
        clock.set(fixture.deadline.minusSeconds(1))
        assertTrue(transitions.isIntentCurrent(intent))
        clock.set(fixture.deadline)

        delivery.deliver(intent)

        verifyNoInteractions(fcm)
    }

    @Test
    fun `device targets are immutable and conditional cleanup matches both id and token`() {
        val userId = owner()
        val originalToken = "synthetic-original-device-token"
        val firstId = device(userId, originalToken)
        val secondId = device(userId, "synthetic-other-device-token")
        val target = deviceStore.targetsFor(userId).single { it.deviceId == firstId }
        assertFalse(target.toString().contains(originalToken))
        assertEquals(0, deviceStore.removeIfTokenMatches(DeviceDeliveryTarget(secondId, originalToken)))

        val rotatedToken = "synthetic-rotated-device-token"
        jdbc.update("UPDATE devices SET fcm_token=? WHERE id=?", rotatedToken, firstId)

        assertEquals(originalToken, target.fcmToken)
        assertEquals(0, deviceStore.removeIfTokenMatches(target))
        val current = deviceStore.targetsFor(userId).single { it.deviceId == firstId }
        assertEquals(rotatedToken, current.fcmToken)
        assertEquals(1, deviceStore.removeIfTokenMatches(current))
        assertEquals(0, deviceStore.removeIfTokenMatches(current))
        assertEquals(listOf(secondId), deviceStore.targetsFor(userId).map { it.deviceId })
    }

    @Test
    fun `invalid device cleanup commits independently of a caller rollback`() {
        val userId = owner()
        device(userId, "synthetic-invalid-device-token")
        val target = deviceStore.targetsFor(userId).single()

        assertFailsWith<IllegalStateException> {
            inTransaction {
                assertEquals(1, deviceStore.removeIfTokenMatches(target))
                error("Synthetic caller rollback")
            }
        }

        assertTrue(deviceStore.targetsFor(userId).isEmpty())
    }

    private fun fixture(kind: SportQueueKind, maxAttempts: Int = 10, force: Boolean = false): DeliveryFixture {
        val userId = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val end = start.plusHours(1)
        val target = lesson(start = start, end = end)
        val candidate = when (kind) {
            SportQueueKind.AUTO -> {
                val prototype = lesson(start = start.minusWeeks(2), end = end.minusWeeks(2))
                auto(userId, prototype, maxAttempts = maxAttempts)
            }
            SportQueueKind.FREE -> free(userId, target, force = force, maxAttempts = maxAttempts)
        }
        val token = "synthetic-delivery-token-${UUID.randomUUID()}"
        device(userId, token)
        val deadline = if (kind == SportQueueKind.FREE && !force) start.minusHours(1).toInstant() else end.toInstant()
        return DeliveryFixture(kind, userId, target, candidate, token, deadline)
    }

    private fun prepare(fixture: DeliveryFixture, bindUnresolved: Boolean = true): SportNotificationIntent =
        assertNotNull(when (fixture.kind) {
            SportQueueKind.AUTO -> transitions.prepareAutoNotification(fixture.candidate, fixture.lessonId, bindUnresolved)
            SportQueueKind.FREE -> transitions.prepareFreeNotification(fixture.candidate, fixture.lessonId)
        })

    private fun cancel(fixture: DeliveryFixture) {
        when (fixture.kind) {
            SportQueueKind.AUTO -> autos.cancelEntry(fixture.ownerId, fixture.candidate.entryId)
            SportQueueKind.FREE -> frees.cancelEntry(fixture.ownerId, fixture.candidate.entryId)
        }
    }

    private fun device(userId: UUID, token: String): UUID = UUID.randomUUID().also { id ->
        jdbc.update("INSERT INTO devices(id, user_id, fcm_token, device_name, last_login) VALUES (?, ?, ?, 'Synthetic test device', ?)",
            id, userId, token, OffsetDateTime.now(clock))
        deliveryDeviceIds.add(id)
    }

    private fun status(fixture: DeliveryFixture): String? =
        jdbc.queryForObject("SELECT status FROM ${table(fixture.kind)} WHERE id=?", String::class.java, fixture.candidate.entryId)

    private fun attempts(fixture: DeliveryFixture): Int? =
        jdbc.queryForObject("SELECT notification_attempts FROM ${table(fixture.kind)} WHERE id=?", Int::class.java, fixture.candidate.entryId)

    private fun assertCancelled(fixture: DeliveryFixture) {
        assertEquals(true, jdbc.queryForObject("SELECT is_cancelled FROM ${table(fixture.kind)} WHERE id=?", Boolean::class.java, fixture.candidate.entryId))
        assertNotNull(jdbc.queryForObject("SELECT cancelled_at FROM ${table(fixture.kind)} WHERE id=?", OffsetDateTime::class.java, fixture.candidate.entryId))
    }

    private fun table(kind: SportQueueKind): String = when (kind) {
        SportQueueKind.AUTO -> "sport_auto_sign_entries"
        SportQueueKind.FREE -> "sport_free_sign_entries"
    }

    private data class DeliveryFixture(
        val kind: SportQueueKind,
        val ownerId: UUID,
        val lessonId: Long,
        val candidate: SportQueueCandidate,
        val token: String,
        val deadline: Instant,
    )
}
