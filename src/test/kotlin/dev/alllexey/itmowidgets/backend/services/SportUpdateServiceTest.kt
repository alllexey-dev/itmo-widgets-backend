package dev.alllexey.itmowidgets.backend.services

import api.myitmo.MyItmo
import api.myitmo.MyItmoApi
import api.myitmo.model.ResultResponse
import api.myitmo.utils.TokenRefreshException
import com.google.gson.JsonSyntaxException
import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DataAccessResourceFailureException
import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.SportSchedule
import api.myitmo.model.sport.SportSignLimit
import api.myitmo.model.sport.TimeSlot
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.configs.MyItmoConfig
import dev.alllexey.itmowidgets.backend.dto.SportCatalogUpdateResult
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.longThat
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.RETURNS_DEFAULTS
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
import retrofit2.Call
import retrofit2.Response
import api.myitmo.model.sport.SportLesson as ApiSportLesson

class SportUpdateServiceTest {
    private val api = mock(MyItmoApi::class.java)
    private val myItmo = MyItmoService(mock(MyItmoTokenStore::class.java), MyItmoConfig()).apply {
        myItmo = MyItmo().apply { api = this@SportUpdateServiceTest.api }
    }
    private val catalog = mock(SportCatalogService::class.java)
    private val updateLogs = mock(SportUpdateLogService::class.java)
    private val freeRepository = mock(SportFreeSignEntryRepository::class.java)
    private val freeNotifications = mock(SportFreeSignNotificationService::class.java)
    private val autoNotifications = mock(SportAutoSignNotificationService::class.java)
    private val autoRepository = mock(SportAutoSignEntryRepository::class.java)
    private val transitions = mock(SportQueueTransitionService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-09-08T21:30:00Z"), ZoneId.of("Europe/Moscow"))
    private val service = SportUpdateService(
        myItmo, catalog, updateLogs, freeRepository, freeNotifications, autoNotifications, autoRepository, transitions, clock,
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
            val row = ApiSportLesson().apply { id = 101 }
            val days = listOf(SportSchedule().apply { lessons = listOf(row) }, SportSchedule())
            schedule(Response.success(envelope(days)))
            `when`(catalog.applySnapshot(eq(listOf(row)) ?: emptyList(), anyLong())).thenReturn(result(mapOf(101L to 0L)))

            service.checkLessonUpdates()

            verify(api).getSportSchedule(from, from.plusDays(21), null, null, null)
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
        schedule(Response.success(envelope(emptyList())))
        `when`(catalog.applySnapshot(eq(emptyList<ApiSportLesson>()) ?: emptyList(), anyLong())).thenReturn(result(emptyMap()))

        service.checkLessonUpdates()

        verify(catalog).applySnapshot(eq(emptyList<ApiSportLesson>()) ?: emptyList(), anyLong())
        verify(autoNotifications).reconcileUnresolvedForecasts(emptyMap())
        assertTrue(logs.list.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 401, 503])
    fun `HTTP 200 error envelope is never accepted even when result is an empty list`(errorCode: Int) {
        schedule(Response.success(envelope(emptyList<SportSchedule>()).apply {
            this.errorCode = errorCode
            errorMessage = SECRET
        }))

        service.checkLessonUpdates()

        verifyNoInteractions(catalog, autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `missing upstream body is rejected without mutating the catalog`() {
        schedule(Response.success(null))

        service.checkLessonUpdates()

        verifyNoInteractions(catalog, autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `missing result is not coerced into successful empty catalog`() {
        schedule(Response.success(ResultResponse()))

        service.checkLessonUpdates()

        verifyNoInteractions(catalog, autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `HTTP failure cannot expose its body or trigger reconciliation`() {
        schedule(Response.error(503, SECRET.toResponseBody()))

        service.checkLessonUpdates()

        verifyNoInteractions(catalog, autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `transport failure is safely contained and a later scheduled retry succeeds`() {
        val call = schedule(Response.success(envelope(emptyList())))
        `when`(call.execute()).thenThrow(IOException(SECRET)).thenReturn(Response.success(envelope(emptyList())))
        `when`(catalog.applySnapshot(eq(emptyList<ApiSportLesson>()) ?: emptyList(), anyLong())).thenReturn(result(emptyMap()))

        service.checkLessonUpdates()
        service.checkLessonUpdates()

        verify(catalog).applySnapshot(eq(emptyList<ApiSportLesson>()) ?: emptyList(), anyLong())
        verify(autoNotifications).reconcileUnresolvedForecasts(emptyMap())
        assertSafeFailure()
    }

    @Test
    fun `catalog persistence failure cannot start queue processing`() {
        schedule(Response.success(envelope(emptyList())))
        doAnswer { throw IllegalStateException(SECRET) }.`when`(catalog).applySnapshot(anyList(), anyLong())

        service.checkLessonUpdates()

        verifyNoInteractions(autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `dictionary refresh rejects error envelopes and retries both dictionaries later`() {
        val timeSlots = call(Response.success(envelope(emptyList<TimeSlot>()).apply { errorCode = 1; errorMessage = SECRET }))
        `when`(api.sportTimeSlots).thenReturn(timeSlots)
        val filters = SportFilters()
        `when`(api.sportFilters).thenReturn(call(Response.success(envelope(filters))))

        service.checkOtherUpdates()
        verifyNoInteractions(catalog)
        `when`(timeSlots.execute()).thenReturn(Response.success(envelope(emptyList())))
        service.checkOtherUpdates()

        verify(catalog).applyTimeSlots(emptyList())
        verify(catalog).applyFilters(filters)
        verifyNoMoreInteractions(catalog)
        assertSafeFailure()
    }

    @Test
    fun `filters error is never applied even after a valid slots response`() {
        `when`(api.sportTimeSlots).thenReturn(call(Response.success(envelope(emptyList()))))
        `when`(api.sportFilters).thenReturn(call(Response.success(envelope(SportFilters()).apply { errorCode = 1 })))

        service.checkOtherUpdates()

        verify(catalog).applyTimeSlots(emptyList())
        verifyNoMoreInteractions(catalog)
        assertSafeFailure()
    }

    @Test
    fun `valid limits reconcile zero capacities and alternate notification queues only after success`() {
        val limit = SportSignLimit().apply { available = 0; this.limit = 20 }
        val limits = hashMapOf(1L to hashMapOf(101L to limit))
        val call = call(Response.success(envelope(limits)))
        `when`(api.sportSignLimits).thenReturn(call)
        `when`(call.execute()).thenThrow(IOException(SECRET))
            .thenReturn(Response.success(envelope(limits)))

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
        `when`(freeRepository.findExpiredCandidates(now)).thenReturn(listOf(first, second))
        `when`(autoRepository.findExpiredCandidates(now.minusWeeks(2))).thenReturn(listOf(first, second))
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
        val failure = when (kind) {
            FailureKind.AUTH -> TokenRefreshException(SECRET)
            FailureKind.NETWORK -> IOException(SECRET)
            FailureKind.MAPPING -> JsonSyntaxException(SECRET)
            FailureKind.PERSISTENCE -> DataIntegrityViolationException(SECRET)
            FailureKind.WRAPPED_PERSISTENCE -> TokenRefreshException(SECRET, DataIntegrityViolationException(SECRET))
            FailureKind.INTERNAL -> IllegalStateException(SECRET)
        }
        val call = schedule(Response.success(envelope(emptyList())))
        `when`(call.execute()).thenThrow(failure)

        service.checkLessonUpdates()

        verify(updateLogs).recordFailure(longThat { it >= 0L }, eq(0), eq(kind.category) ?: kind.category)
        verifyNoMoreInteractions(updateLogs)
        verifyNoInteractions(catalog, autoNotifications)
        assertSafeFailure()
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `upstream authentication HTTP failures are distinguishable without storing a response body`(status: Int) {
        schedule(Response.error(status, SECRET.toResponseBody()))

        service.checkLessonUpdates()

        verify(updateLogs).recordFailure(longThat { it >= 0L }, eq(0),
            eq(SportUpdateErrorCategory.AUTH) ?: SportUpdateErrorCategory.AUTH)
        verifyNoInteractions(catalog, autoNotifications)
        assertSafeFailure()
    }

    @Test
    fun `failed log storage does not retry catalog or acknowledge success`() {
        schedule(Response.error(503, SECRET.toResponseBody()))
        doThrow(DataAccessResourceFailureException(SECRET)).`when`(updateLogs).recordFailure(
            anyLong(), anyInt(), eq(SportUpdateErrorCategory.HTTP) ?: SportUpdateErrorCategory.HTTP,
        )

        service.checkLessonUpdates()

        verify(updateLogs).recordFailure(longThat { it >= 0L }, eq(0),
            eq(SportUpdateErrorCategory.HTTP) ?: SportUpdateErrorCategory.HTTP)
        verifyNoMoreInteractions(updateLogs)
        verifyNoInteractions(catalog, autoNotifications)
        assertTrue(logs.list.any { it.formattedMessage.contains("failure log unavailable") })
        assertSafeFailure()
    }

    @Test
    fun `queue failure after catalog commit cannot record a false failed refresh`() {
        schedule(Response.success(envelope(emptyList())))
        `when`(catalog.applySnapshot(anyList(), anyLong())).thenReturn(result(emptyMap()))
        doThrow(IllegalStateException(SECRET)).`when`(autoNotifications).reconcileUnresolvedForecasts(emptyMap())

        service.checkLessonUpdates()

        verify(catalog).applySnapshot(eq(emptyList<ApiSportLesson>()) ?: emptyList(), anyLong())
        verifyNoInteractions(updateLogs)
        assertSafeFailure()
    }

    enum class FailureKind(val category: SportUpdateErrorCategory) {
        AUTH(SportUpdateErrorCategory.AUTH), NETWORK(SportUpdateErrorCategory.NETWORK),
        MAPPING(SportUpdateErrorCategory.MAPPING), PERSISTENCE(SportUpdateErrorCategory.PERSISTENCE),
        WRAPPED_PERSISTENCE(SportUpdateErrorCategory.PERSISTENCE), INTERNAL(SportUpdateErrorCategory.INTERNAL),
    }

    private fun result(capacities: Map<Long, Long>) = SportCatalogUpdateResult(capacities, 0, 0, 0, 0)

    private fun schedule(response: Response<ResultResponse<List<SportSchedule>>>): Call<ResultResponse<List<SportSchedule>>> =
        call(response).also { `when`(api.getSportSchedule(from, from.plusDays(21), null, null, null)).thenReturn(it) }

    private fun assertSafeFailure() {
        assertTrue(logs.list.isNotEmpty())
        logs.list.forEach {
            assertFalse(it.formattedMessage.contains(SECRET))
            assertTrue(it.throwableProxy == null)
        }
    }

    private fun <T> envelope(result: T): ResultResponse<T> = ResultResponse<T>().apply { this.result = result }

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(response: Response<T>): Call<T> = mock(Call::class.java) { invocation ->
        if (invocation.method.name == "execute") response else RETURNS_DEFAULTS.answer(invocation)
    } as Call<T>

    companion object {
        private const val SECRET = "synthetic-upstream-private-response"
    }
}
