package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.backend.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.services.LessonService
import dev.alllexey.itmowidgets.backend.services.LessonService.Companion.toEntity
import dev.alllexey.itmowidgets.backend.services.UserRegistrationService
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.framework.AopInfrastructureBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Import(LessonService::class, UserRegistrationService::class, LessonSyncConcurrencyTest.SyncConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LessonSyncConcurrencyTest @Autowired constructor(
    private val service: LessonService,
    private val registration: UserRegistrationService,
    private val lessons: LessonRepository,
    private val jdbc: JdbcTemplate,
    private val gate: ScheduleOwnerLockGate,
) : PostgreSqlRepositoryTest() {
    private val owners = mutableListOf<Int>()

    @BeforeEach
    fun resetGate() = gate.reset()

    @AfterEach
    fun removeCommittedFixtures() {
        gate.reset()
        owners.forEach { isu ->
            jdbc.update("DELETE FROM lessons WHERE user_isu = ?", isu)
            jdbc.update("DELETE FROM users WHERE isu = ?", isu)
        }
    }

    @Test
    fun `concurrent disjoint snapshots of an empty range keep only the last serialized writer`() {
        val isu = owner()
        val before = lesson(isu, 10, DAY.minusDays(1))
        val after = lesson(isu, 11, DAY.plusDays(1))
        service.syncLessons(isu, before.date, after.date, listOf(before, after))
        val first = listOf(lesson(isu, 20), lesson(isu, 21))
        val second = listOf(lesson(isu, 30), lesson(isu, 31))

        // No existing row in DAY can accidentally serialize the two DELETEs instead of the owner lock.
        serialized(isu,
            first = { service.syncLessons(isu, DAY, DAY, first) },
            second = { service.syncLessons(isu, DAY, DAY, second) },
        )

        assertSnapshot(isu, listOf(before, after) + second)
    }

    @Test
    fun `concurrent empty snapshot clears the requested range without deleting outside it`() {
        val isu = owner()
        val outside = lesson(isu, 10, DAY.minusDays(1))
        service.syncLessons(isu, outside.date, DAY, listOf(outside))

        serialized(isu,
            first = { service.syncLessons(isu, DAY, DAY, listOf(lesson(isu, 20))) },
            second = { service.syncLessons(isu, DAY, DAY, emptyList()) },
        )

        assertSnapshot(isu, listOf(outside))
    }

    @Test
    fun `overlapping ranges serialize while the first writers nonoverlap and external rows survive`() {
        val isu = owner()
        val outside = listOf(lesson(isu, 10, DAY.minusDays(1)), lesson(isu, 11, DAY.plusDays(3)))
        service.syncLessons(isu, DAY.minusDays(1), DAY.plusDays(3), outside)
        val retainedFirst = lesson(isu, 20, DAY)
        val removedFirst = lesson(isu, 21, DAY.plusDays(1))
        val second = listOf(lesson(isu, 30, DAY.plusDays(1)), lesson(isu, 31, DAY.plusDays(2)))

        serialized(isu,
            first = { service.syncLessons(isu, DAY, DAY.plusDays(1), listOf(retainedFirst, removedFirst)) },
            second = { service.syncLessons(isu, DAY.plusDays(1), DAY.plusDays(2), second) },
        )

        assertSnapshot(isu, outside + retainedFirst + second)
    }

    @Test
    fun `different owners synchronize the same pair without waiting for another owners transaction`() {
        val firstIsu = owner()
        val secondIsu = owner()
        val firstLesson = lesson(firstIsu, 10)
        val secondLesson = lesson(secondIsu, 10)
        val executor = Executors.newFixedThreadPool(2)
        gate.arm(firstIsu)
        try {
            val first = executor.submit { service.syncLessons(firstIsu, DAY, DAY, listOf(firstLesson)) }
            gate.awaitFirstLocked()
            val second = executor.submit { service.syncLessons(secondIsu, DAY, DAY, listOf(secondLesson)) }
            second.get(10, TimeUnit.SECONDS)
            assertSnapshot(secondIsu, listOf(secondLesson))
            assertSnapshot(firstIsu, emptyList())
            gate.release()
            first.get(10, TimeUnit.SECONDS)
            assertSnapshot(firstIsu, listOf(firstLesson))
        } finally {
            gate.release()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `repeated public sync updates a moved pair without replacing its original row identity`() {
        val isu = owner()
        val original = lesson(isu, 10, id = UUID.randomUUID())
        service.syncLessons(isu, DAY, DAY, listOf(original))
        val updated = original.toDto().copy(
            date = DAY.plusDays(1), subjectName = "Updated synthetic subject", room = "New room",
        ).toEntity(isu)
        assertNotEquals(original.id, updated.id)

        repeat(2) { service.syncLessons(isu, DAY, DAY.plusDays(1), listOf(updated)) }

        val saved = lessons.findAllByIsuAndDates(isu, DAY, DAY.plusDays(1)).single()
        assertEquals(original.id, saved.id)
        assertEquals(updated.toDto(), saved.toDto())
    }

    @Test
    fun `SQL failure after deletion and an earlier batch update rolls back the complete snapshot`() {
        val isu = owner()
        val removed = lesson(isu, 10)
        val retained = lesson(isu, 20)
        service.syncLessons(isu, DAY, DAY, listOf(removed, retained))
        val updated = retained.toDto().copy(subjectName = "Must roll back").toEntity(isu)
        // Distinct valid pair IDs pass payload validation; the later row violates the actual PostgreSQL PK.
        val conflict = lesson(isu, 30, id = retained.id)

        assertFailsWith<DataIntegrityViolationException> {
            service.syncLessons(isu, DAY, DAY, listOf(updated, conflict))
        }

        assertSnapshot(isu, listOf(removed, retained))
    }

    @Test
    fun `invalid range does not change the existing snapshot`() {
        val isu = owner()
        val existing = lesson(isu, 10)
        service.syncLessons(isu, DAY, DAY, listOf(existing))

        val error = assertFailsWith<InvalidRequestDataException> {
            service.syncLessons(isu, DAY.plusDays(1), DAY, emptyList())
        }

        assertEquals("Invalid schedule date range", error.message)
        assertSnapshot(isu, listOf(existing))
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 2])
    fun `a lesson outside either inclusive boundary leaves the complete snapshot unchanged`(offset: Long) {
        val isu = owner()
        val existing = lesson(isu, 10)
        service.syncLessons(isu, DAY, DAY, listOf(existing))
        val otherwiseValid = lesson(isu, 20)
        val invalid = lesson(isu, 30, DAY.plusDays(offset))

        val error = assertFailsWith<InvalidRequestDataException> {
            service.syncLessons(isu, DAY, DAY.plusDays(1), listOf(otherwiseValid, invalid))
        }

        assertEquals("Schedule lesson date is outside requested range", error.message)
        assertSnapshot(isu, listOf(existing))
    }

    @Test
    fun `duplicate pair IDs across dates reject the whole snapshot instead of partially updating it`() {
        val isu = owner()
        val existing = lesson(isu, 10)
        service.syncLessons(isu, DAY, DAY, listOf(existing))

        val error = assertFailsWith<InvalidRequestDataException> {
            service.syncLessons(isu, DAY, DAY.plusDays(1), listOf(lesson(isu, 20), lesson(isu, 20, DAY.plusDays(1))))
        }

        assertEquals("Schedule snapshot contains duplicate pair IDs", error.message)
        assertSnapshot(isu, listOf(existing))
    }

    @Test
    fun `foreign owner in a later row changes neither owners snapshot`() {
        val isu = owner()
        val foreignIsu = owner()
        val existing = lesson(isu, 10)
        val foreign = lesson(foreignIsu, 10)
        service.syncLessons(isu, DAY, DAY, listOf(existing))
        service.syncLessons(foreignIsu, DAY, DAY, listOf(foreign))

        val error = assertFailsWith<InvalidRequestDataException> {
            service.syncLessons(isu, DAY, DAY, listOf(lesson(isu, 20), lesson(foreignIsu, 30)))
        }

        assertEquals("Schedule lesson belongs to another user", error.message)
        assertSnapshot(isu, listOf(existing))
        assertSnapshot(foreignIsu, listOf(foreign))
    }

    @Test
    fun `owner removed before locking cannot silently mutate an orphaned cached snapshot`() {
        val isu = owner()
        val existing = lesson(isu, 10)
        service.syncLessons(isu, DAY, DAY, listOf(existing))
        jdbc.update("DELETE FROM users WHERE isu = ?", isu)

        assertFailsWith<NotFoundException> { service.syncLessons(isu, DAY, DAY, emptyList()) }

        assertSnapshot(isu, listOf(existing))
    }

    private fun owner(): Int = nextIsu.getAndIncrement().also { isu ->
        registration.findOrCreateByIsu(isu)
        owners.add(isu)
    }

    private fun assertSnapshot(isu: Int, expected: List<LessonEntity>) {
        val actual = lessons.findAllByIsuAndDates(isu, DAY.minusDays(10), DAY.plusDays(10))
        assertEquals(expected.associate { it.id to it.toDto() }, actual.associate { it.id to it.toDto() })
    }

    private fun serialized(isu: Int, first: () -> Unit, second: () -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        gate.arm(isu)
        try {
            val firstResult = executor.submit { first() }
            gate.awaitFirstLocked()
            val secondResult = executor.submit { second() }
            // The test observes an actual PostgreSQL lock wait, not just a thread-start signal.
            gate.awaitSecondBlocked()
            gate.release()
            firstResult.get(10, TimeUnit.SECONDS)
            secondResult.get(10, TimeUnit.SECONDS)
        } finally {
            gate.release()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    private fun lesson(
        isu: Int,
        pairId: Long,
        date: LocalDate = DAY,
        id: UUID = UUID.nameUUIDFromBytes("$pairId-$isu".toByteArray()),
    ) = LessonEntity(
        userIsu = isu, pairId = pairId, date = date, id = id,
        subjectId = 1, subjectName = "Synthetic subject", teacherIsu = null, teacherFio = null,
        start = LocalTime.of(9, 0), end = LocalTime.of(10, 30), type = "Лекция", typeId = 1,
        groupName = "M3100", flowId = 1, flowTypeId = 1, note = null, room = null, building = null,
        buildingId = null, mainBuildingId = null, format = "Очно", formatId = 1,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class SyncConfig {
        @Bean fun scheduleOwnerLockGate(jdbc: JdbcTemplate) = ScheduleOwnerLockGate(jdbc)

        @Bean
        @Primary
        fun gatedUsers(
            @Qualifier("userRepository") delegate: UserRepository,
            gate: ScheduleOwnerLockGate,
        ): UserRepository = object : UserRepository by delegate, AopInfrastructureBean {
            // The real Spring Data proxy already owns interception; do not CGLIB-proxy this final decorator.
            override fun lockByIsu(isu: Int): UUID? = gate.lock(isu) { delegate.lockByIsu(isu) }
        }
    }

    companion object {
        private val DAY = LocalDate.parse("2026-09-08")
        private val nextIsu = AtomicInteger(990001)
    }
}

/** Only coordinates test threads; production mutation and locking remain in the public service. */
class ScheduleOwnerLockGate(private val jdbc: JdbcTemplate) {
    @Volatile private var owner: Int? = null
    private val visits = AtomicInteger()
    private var firstLocked = CountDownLatch(1)
    private var secondAttempt = CountDownLatch(1)
    private var releaseFirst = CountDownLatch(1)
    @Volatile private var firstPid = 0
    @Volatile private var secondPid = 0

    fun arm(isu: Int) {
        reset()
        visits.set(0)
        firstLocked = CountDownLatch(1)
        secondAttempt = CountDownLatch(1)
        releaseFirst = CountDownLatch(1)
        owner = isu
    }

    fun lock(isu: Int, action: () -> UUID?): UUID? {
        if (owner != isu) return action()
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        val visit = visits.incrementAndGet()
        val pid = checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
        if (visit == 2) {
            secondPid = pid
            secondAttempt.countDown()
        }
        val result = action()
        if (visit == 1) {
            firstPid = pid
            firstLocked.countDown()
            // Longer than both readiness deadlines combined; readiness still fails after its own bounded wait.
            assertTrue(releaseFirst.await(45, TimeUnit.SECONDS), "First schedule transaction was not released")
        }
        return result
    }

    fun awaitFirstLocked() = assertTrue(firstLocked.await(10, TimeUnit.SECONDS))

    fun awaitSecondBlocked() {
        assertTrue(secondAttempt.await(10, TimeUnit.SECONDS))
        assertNotEquals(firstPid, secondPid)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            if (jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean::class.java, firstPid, secondPid) == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        error("Second snapshot did not block on the first owner row")
    }

    fun release() = releaseFirst.countDown()
    fun reset() { owner = null; release() }
}
