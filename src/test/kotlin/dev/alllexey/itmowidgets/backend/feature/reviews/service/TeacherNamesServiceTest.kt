package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoService
import api.myitmo.MyItmo
import api.myitmo.MyItmoApi
import api.myitmo.model.ResultResponse
import api.myitmo.model.personality.Personality
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.transaction.support.TransactionSynchronizationManager
import retrofit2.Call
import retrofit2.Response

class TeacherNamesServiceTest {
    private val clock = MutableClock()
    private val source = FakeSource()
    private val service = TeacherNamesService(source, clock)

    @Test fun `a name is cached for twelve hours`() {
        source.value = "Иванов Иван Иванович"
        assertEquals("Иванов Иван Иванович", service.name(TEACHER))
        source.value = "Петров Пётр Петрович"
        clock.advance(Duration.ofHours(12).minusSeconds(1))
        assertEquals("Иванов Иван Иванович", service.name(TEACHER))
        assertEquals(listOf(TEACHER), source.calls)

        clock.advance(Duration.ofSeconds(1))
        assertEquals("Петров Пётр Петрович", service.name(TEACHER))
        assertEquals(2, source.calls.size)
    }

    @Test fun `a missing name is cached like a found one`() {
        source.value = null
        assertNull(service.name(TEACHER))
        assertNull(service.name(TEACHER))
        assertEquals(listOf(TEACHER), source.calls)
    }

    @Test fun `a temporary failure keeps the last name and pauses every lookup for thirty seconds`() {
        source.value = "Иванов Иван Иванович"
        service.name(TEACHER)
        clock.advance(Duration.ofHours(12))
        source.failure = PersonNameUnavailable(temporary = true)

        assertEquals("Иванов Иван Иванович", service.name(TEACHER))
        assertNull(service.name(OTHER_TEACHER))
        assertEquals(listOf(TEACHER, TEACHER), source.calls, "The outage pauses lookups of other teachers")

        clock.advance(Duration.ofSeconds(30))
        source.failure = null
        source.value = "Петров Пётр Петрович"
        assertEquals("Петров Пётр Петрович", service.name(OTHER_TEACHER))
        assertEquals("Петров Пётр Петрович", service.name(TEACHER))
        assertEquals(listOf(TEACHER, TEACHER, OTHER_TEACHER, TEACHER), source.calls)
    }

    @Test fun `a failure for one person retries that person only after thirty seconds`() {
        source.failure = PersonNameUnavailable(temporary = false)
        assertNull(service.name(TEACHER))
        assertNull(service.name(TEACHER))
        source.failure = null
        source.value = "Петров Пётр Петрович"
        assertEquals("Петров Пётр Петрович", service.name(OTHER_TEACHER))
        assertEquals(listOf(TEACHER, OTHER_TEACHER), source.calls)
        clock.advance(Duration.ofSeconds(30))
        assertEquals("Петров Пётр Петрович", service.name(TEACHER))
    }

    @Test fun `the MyITMO source reads only the trimmed name of the requested person`() {
        val source = directory { Response.success(body(TEACHER, "  Иванов Иван Иванович  ")) }
        assertEquals("Иванов Иван Иванович", source.name(TEACHER))
        assertEquals(TimeUnit.SECONDS.toNanos(3), lastTimeout.timeoutNanos())
        assertNull(directory { Response.success(body(OTHER_TEACHER, "Петров Пётр Петрович")) }.name(TEACHER))
        assertNull(directory { Response.success(body(TEACHER, "   ")) }.name(TEACHER))
        assertNull(directory { Response.success(body(TEACHER, null)) }.name(TEACHER))
    }

    @Test fun `MyITMO failures are temporary only for outages`() {
        for ((response, temporary) in listOf(
            Response.error<ResultResponse<Personality>>(503, "synthetic upstream content".toResponseBody()) to true,
            Response.error<ResultResponse<Personality>>(429, "synthetic upstream content".toResponseBody()) to true,
            Response.error<ResultResponse<Personality>>(404, "synthetic upstream content".toResponseBody()) to false,
            Response.success(body(TEACHER, "Имя").apply { errorCode = 27 }) to false,
            Response.success(ResultResponse<Personality>()) to false,
        )) {
            val error = assertFailsWith<PersonNameUnavailable> { directory { response }.name(TEACHER) }
            assertEquals(temporary, error.temporary)
            assertEquals("Person name unavailable", error.message)
        }
        assertTrue(assertFailsWith<PersonNameUnavailable> { directory { throw IOException("synthetic") }.name(TEACHER) }.temporary)
    }

    @Test fun `the MyITMO source refuses to run inside a transaction`() {
        val api = mock(MyItmoApi::class.java)
        val myItmoService = mock(MyItmoService::class.java)
        `when`(myItmoService.myItmo).thenReturn(MyItmo().apply { this.api = api })
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try { assertFailsWith<IllegalStateException> { MyItmoPersonNamesSource(myItmoService).name(TEACHER) } }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false) }
        verifyNoInteractions(api)
    }

    private var lastTimeout = Timeout()

    private fun directory(answer: () -> Response<ResultResponse<Personality>>): MyItmoPersonNamesSource {
        val api = mock(MyItmoApi::class.java)
        @Suppress("UNCHECKED_CAST")
        val call = mock(Call::class.java) as Call<ResultResponse<Personality>>
        lastTimeout = Timeout()
        `when`(api.getPersonality(TEACHER)).thenReturn(call)
        `when`(call.timeout()).thenReturn(lastTimeout)
        `when`(call.execute()).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            answer()
        }
        val myItmoService = mock(MyItmoService::class.java)
        `when`(myItmoService.myItmo).thenReturn(MyItmo().apply { this.api = api })
        return MyItmoPersonNamesSource(myItmoService)
    }

    private fun body(isu: Int, fio: String?) = ResultResponse<Personality>().apply {
        result = Personality().apply { this.isu = isu.toLong(); this.fio = fio }
    }

    private class FakeSource : OfficialPersonNamesSource {
        val calls = mutableListOf<Int>()
        var value: String? = null
        var failure: PersonNameUnavailable? = null
        override fun name(isu: Int): String? {
            calls += isu
            failure?.let { throw it }
            return value
        }
    }

    private class MutableClock : Clock() {
        private var time = Instant.parse("2026-09-24T09:00:00Z")
        override fun instant() = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(duration: Duration) { time = time.plus(duration) }
    }

    private companion object {
        const val TEACHER = 142415
        const val OTHER_TEACHER = 471029
    }
}
