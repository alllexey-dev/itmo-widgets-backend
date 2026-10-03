package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoPersonality
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import dev.alllexey.itmowidgets.backend.testing.FakeMyItmoGateway
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

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
        val gateway = FakeMyItmoGateway()
        val source = directory(gateway) { MyItmoResult.Success(profile(TEACHER, "  Иванов Иван Иванович  ")) }
        assertEquals("Иванов Иван Иванович", source.name(TEACHER))
        assertEquals(listOf("personality $TEACHER"), gateway.calls)
        assertNull(directory { MyItmoResult.Success(profile(OTHER_TEACHER, "Петров Пётр Петрович")) }.name(TEACHER))
        assertNull(directory { MyItmoResult.Success(profile(TEACHER, "   ")) }.name(TEACHER))
        assertNull(directory { MyItmoResult.Success(profile(TEACHER, null)) }.name(TEACHER))
    }

    @Test fun `MyITMO failures are temporary only for outages`() {
        for ((answer, temporary) in listOf(
            MyItmoResult.HttpStatus(503) to true,
            MyItmoResult.HttpStatus(429) to true,
            MyItmoResult.HttpStatus(404) to false,
            MyItmoResult.InvalidEnvelope to false,
            MyItmoResult.TransportFailed(IOException("synthetic")) to true,
        )) {
            val error = assertFailsWith<PersonNameUnavailable> { directory { answer }.name(TEACHER) }
            assertEquals(temporary, error.temporary)
            assertEquals("Person name unavailable", error.message)
        }
        val thrown = directory { throw IllegalStateException("synthetic") }
        assertTrue(assertFailsWith<PersonNameUnavailable> { thrown.name(TEACHER) }.temporary)
    }

    @Test fun `the MyITMO source refuses to run inside a transaction`() {
        val gateway = FakeMyItmoGateway()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertFailsWith<IllegalStateException> { MyItmoPersonNamesSource(gateway).name(TEACHER) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        assertEquals(emptyList(), gateway.calls)
    }

    private fun directory(
        gateway: FakeMyItmoGateway = FakeMyItmoGateway(),
        answer: () -> MyItmoResult<MyItmoPersonality>,
    ): MyItmoPersonNamesSource {
        gateway.personality = { isu ->
            assertEquals(TEACHER, isu)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            answer()
        }
        return MyItmoPersonNamesSource(gateway)
    }

    private fun profile(isu: Int, fio: String?) = MyItmoPersonality(isu.toLong(), fio, education = null)

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
        fun advance(duration: Duration) {
            time = time.plus(duration)
        }
    }

    private companion object {
        const val TEACHER = 142415
        const val OTHER_TEACHER = 471029
    }
}
