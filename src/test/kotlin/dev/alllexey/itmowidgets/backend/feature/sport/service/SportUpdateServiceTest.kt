package dev.alllexey.itmowidgets.backend.feature.sport.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoConfig
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoService
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoSportFilters
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoSportLesson
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoSportSignLimit
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.testing.FakeMyItmoGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.longThat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SportUpdateServiceTest {
    private val gateway = FakeMyItmoGateway()
    private val credentials = mock(ServiceCredentialStore::class.java)
    private val myItmo = MyItmoService(credentials, MyItmoConfig())
    private val catalog = mock(SportCatalogService::class.java)
    private val updateLogs = mock(SportUpdateLogService::class.java)
    private val freeRepository = mock(SportFreeSignEntryRepository::class.java)
    private val freeNotifications = mock(SportFreeSignNotificationService::class.java)
    private val autoNotifications = mock(SportAutoSignNotificationService::class.java)
    private val autoRepository = mock(SportAutoSignEntryRepository::class.java)
    private val transitions = mock(SportQueueTransitionService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-09-08T21:30:00Z"), ZoneId.of("Europe/Moscow"))
    private val service = SportUpdateService(
        gateway, myItmo, catalog, updateLogs, freeRepository, freeNotifications, autoNotifications, autoRepository, transitions, clock,
    )
    private val logger = LoggerFactory.getLogger(SportUpdateService::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()
    private val from = LocalDate.parse("2026-09-09")

    @BeforeEach
    fun captureLogs() {
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        logger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `catalog window uses one Moscow date even before UTC midnight and commits before reconciliation`() {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val row = MyItmoSportLesson(id = 101)
            schedule(MyItmoResult.Success(listOf(row)))
            `when`(catalog.applySnapshot(eq(listOf(row)) ?: emptyList(), anyLong())).thenReturn(result(mapOf(101L to 0L)))

            service.checkLessonUpdates()

            assertEquals(listOf("sportSchedule $from..${from.plusDays(21)}"), gateway.calls)
            val order = inOrder(catalog, autoNotifications)
            order.verify(catalog).applySnapshot(eq(listOf(row)) ?: emptyList(), anyLong())
            order.verify(autoNotifications).reconcileUnresolvedForecasts(mapOf(101L to 0L))
            order.verifyNoMoreInteractions()
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `successful empty catalog remains a valid snapshot and not evidence of deletions`() {
        schedule(MyItmoResult.Success(emptyList()))
        `when`(catalog.applySnapshot(eq(emptyList<MyItmoSportLesson>()) ?: emptyList(), anyLong())).thenReturn(result(emptyMap()))

        service.checkLessonUpdates()

        verify(catalog).applySnapshot(eq(emptyList<MyItmoSportLesson>()) ?: emptyList(), anyLong())
        verify(autoNotifications).reconcileUnresolvedForecasts(emptyMap())
        assertTrue(logs.list.isEmpty())
    }

    @Test
    fun `transport failure is safely contained and a later scheduled retry succeeds`() {
        val answers = answersInOrder<List<MyItmoSportLesson?>>(
            MyItmoResult.TransportFailed(IOException(SECRET)),
            MyItmoResult.Success(emptyList()),
        )
        gateway.schedule = { _, _ -> answers() }
        `when`(catalog.applySnapshot(eq(emptyList<MyItmoSportLesson>()) ?: emptyList(), anyLong())).thenReturn(result(emptyMap()))

        service.checkLessonUpdates()
        service.checkLessonUpdates()

        verify(catalog).applySnapshot(eq(emptyList<MyItmoSportLesson>()) ?: emptyList(), anyLong())
        verify(autoNotifications).reconcileUnresolvedForecasts(emptyMap())
        assertSafeFailure()
    }

    @Test
    fun `catalog persistence failure cannot start queue processing`() {
        schedule(MyItmoResult.Success(emptyList()))
        doAnswer { throw IllegalStateException(SECRET) }.`when`(catalog).applySnapshot(anyList(), anyLong())

        service.checkLessonUpdates()

        verifyNoInteractions(autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `dictionary refresh rejects error envelopes and retries both dictionaries later`() {
        gateway.timeSlots = answersInOrder(MyItmoResult.InvalidEnvelope, MyItmoResult.Success(emptyList()))
        val filters = MyItmoSportFilters()
        gateway.filters = { MyItmoResult.Success(filters) }

        service.checkOtherUpdates()
        verifyNoInteractions(catalog)
        service.checkOtherUpdates()

        verify(catalog).applyTimeSlots(emptyList())
        verify(catalog).applyFilters(filters)
        verifyNoMoreInteractions(catalog)
        assertSafeFailure()
    }

    @Test
    fun `filters error is never applied even after a valid slots response`() {
        gateway.timeSlots = { MyItmoResult.Success(emptyList()) }
        gateway.filters = { MyItmoResult.InvalidEnvelope }

        service.checkOtherUpdates()

        verify(catalog).applyTimeSlots(emptyList())
        verifyNoMoreInteractions(catalog)
        assertSafeFailure()
    }

    @Test
    fun `valid limits reconcile zero capacities and alternate notification queues only after success`() {
        val limit = MyItmoSportSignLimit(limit = 20, available = 0)
        gateway.signLimits = answersInOrder(
            MyItmoResult.TransportFailed(IOException(SECRET)),
            MyItmoResult.Success(mapOf(101L to limit)),
        )

        service.processSportLimits()
        verifyNoInteractions(autoNotifications, freeNotifications)
        service.processSportLimits()
        service.processSportLimits()

        verify(autoNotifications, times(2)).reconcileUnresolvedForecasts(mapOf(101L to 0L))
        verify(freeNotifications).sendNotificationsForFreeLessons(mapOf(101L to limit))
        verify(autoNotifications).sendNotificationsForAvailableLessons(mapOf(101L to limit))
        assertSafeFailure()
    }

    @Test
    fun `expiry uses the injected clock and isolates a failed owner from following candidates`() {
        val first = SportQueueCandidate(1L, UUID(0, 1))
        val second = SportQueueCandidate(2L, UUID(0, 2))
        val now = OffsetDateTime.now(clock)
        `when`(freeRepository.findExpiredCandidates(SportQueueRules.freeExpiryHorizon(now)))
            .thenReturn(listOf(first, second))
        // The forecast offset is frozen into the snapshot, so expiry compares against plain now.
        `when`(autoRepository.findExpiredCandidates(now)).thenReturn(listOf(first, second))
        doThrow(IllegalStateException(SECRET)).`when`(transitions).expireFreeEntry(first)
        doThrow(IllegalStateException(SECRET)).`when`(transitions).expireAutoEntry(first)

        service.cleanupExpiredFreeSignEntries()
        service.cleanupExpiredAutoSignEntries()

        verify(transitions).expireFreeEntry(second)
        verify(transitions).expireAutoEntry(second)
        assertSafeFailure()
    }

    @ParameterizedTest
    @EnumSource(FailureKind::class)
    fun `refresh failures record only a safe category and nonnegative elapsed time`(kind: FailureKind) {
        gateway.schedule = { _, _ -> kind.answer() }

        service.checkLessonUpdates()

        verify(updateLogs).recordFailure(longThat { it >= 0L }, eq(0), eq(kind.category) ?: kind.category)
        verifyNoMoreInteractions(updateLogs)
        verifyNoInteractions(catalog, autoNotifications)
        if (kind.category == SportUpdateErrorCategory.AUTH) verifyAuthFailureRecorded() else verifyNoInteractions(credentials)
        assertSafeFailure()
    }

    @Test
    fun `failed credential status write neither hides the refresh log nor escapes the scheduler`() {
        schedule(MyItmoResult.HttpStatus(401))
        doThrow(DataAccessResourceFailureException(SECRET)).`when`(credentials).recordFailure(
            ServiceCredential.MY_ITMO_REFRESH_TOKEN,
            ServiceCredentialStatus.FAILED,
            "AUTH sport",
        )

        service.checkLessonUpdates()

        verify(updateLogs).recordFailure(
            longThat { it >= 0L },
            eq(0),
            eq(SportUpdateErrorCategory.AUTH) ?: SportUpdateErrorCategory.AUTH,
        )
        verifyAuthFailureRecorded()
        assertTrue(logs.list.any { it.formattedMessage.contains("credential status unavailable") })
        assertSafeFailure()
    }

    @Test
    fun `failed log storage does not retry catalog or acknowledge success`() {
        schedule(MyItmoResult.HttpStatus(503))
        doThrow(DataAccessResourceFailureException(SECRET)).`when`(updateLogs).recordFailure(
            anyLong(),
            anyInt(),
            eq(SportUpdateErrorCategory.HTTP) ?: SportUpdateErrorCategory.HTTP,
        )

        service.checkLessonUpdates()

        verify(updateLogs).recordFailure(
            longThat { it >= 0L },
            eq(0),
            eq(SportUpdateErrorCategory.HTTP) ?: SportUpdateErrorCategory.HTTP,
        )
        verifyNoMoreInteractions(updateLogs)
        verifyNoInteractions(catalog, autoNotifications, credentials)
        assertTrue(logs.list.any { it.formattedMessage.contains("failure log unavailable") })
        assertSafeFailure()
    }

    @Test
    fun `queue failure after catalog commit cannot record a false failed refresh`() {
        schedule(MyItmoResult.Success(emptyList()))
        `when`(catalog.applySnapshot(anyList(), anyLong())).thenReturn(result(emptyMap()))
        doThrow(IllegalStateException(SECRET)).`when`(autoNotifications).reconcileUnresolvedForecasts(emptyMap())

        service.checkLessonUpdates()

        verify(catalog).applySnapshot(eq(emptyList<MyItmoSportLesson>()) ?: emptyList(), anyLong())
        verifyNoInteractions(updateLogs)
        assertSafeFailure()
    }

    /** What the gateway answered or threw, and the category the refresh log records for it. */
    enum class FailureKind(val category: SportUpdateErrorCategory, val answer: () -> MyItmoResult<List<MyItmoSportLesson?>>) {
        CREDENTIAL_REFRESH(SportUpdateErrorCategory.AUTH, { MyItmoResult.CredentialRefreshFailed(IllegalStateException(SECRET)) }),
        UNAUTHORIZED(SportUpdateErrorCategory.AUTH, { MyItmoResult.HttpStatus(401) }),
        FORBIDDEN(SportUpdateErrorCategory.AUTH, { MyItmoResult.HttpStatus(403) }),
        HTTP_STATUS(SportUpdateErrorCategory.HTTP, { MyItmoResult.HttpStatus(503) }),
        INVALID_ENVELOPE(SportUpdateErrorCategory.HTTP, { MyItmoResult.InvalidEnvelope }),
        NETWORK(SportUpdateErrorCategory.NETWORK, { MyItmoResult.TransportFailed(IOException(SECRET)) }),
        MAPPING(SportUpdateErrorCategory.MAPPING, { MyItmoResult.MalformedBody(IllegalStateException(SECRET)) }),
        PERSISTENCE(SportUpdateErrorCategory.PERSISTENCE, { throw DataIntegrityViolationException(SECRET) }),
        WRAPPED_PERSISTENCE(SportUpdateErrorCategory.PERSISTENCE, {
            throw IllegalStateException(SECRET, DataIntegrityViolationException(SECRET))
        }),
        INTERNAL(SportUpdateErrorCategory.INTERNAL, { throw IllegalStateException(SECRET) }),
    }

    private fun verifyAuthFailureRecorded() {
        verify(credentials).recordFailure(ServiceCredential.MY_ITMO_REFRESH_TOKEN, ServiceCredentialStatus.FAILED, "AUTH sport")
        verifyNoMoreInteractions(credentials)
    }

    private fun result(capacities: Map<Long, Long>) = SportCatalogUpdateResult(capacities, 0, 0, 0, 0)

    /** Each answer once, then the last one again, like a stub with several return values. */
    private fun <T> answersInOrder(vararg answers: MyItmoResult<T>): () -> MyItmoResult<T> {
        val queue = ArrayDeque(answers.toList())
        return { if (queue.size > 1) queue.removeFirst() else queue.first() }
    }

    private fun schedule(answer: MyItmoResult<List<MyItmoSportLesson?>>) {
        gateway.schedule = { _, _ -> answer }
    }

    /** The rendered line stays a safe category; the cause chain rides along for operators. */
    private fun assertSafeFailure() {
        assertTrue(logs.list.isNotEmpty())
        logs.list.forEach {
            assertFalse(it.formattedMessage.contains(SECRET))
            assertNotNull(it.throwableProxy)
        }
    }

    companion object {
        private const val SECRET = "synthetic-upstream-private-response"
    }
}
