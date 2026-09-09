package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.MyItmo
import api.myitmo.MyItmoApi
import api.myitmo.model.ResultResponse
import api.myitmo.model.sport.SportSchedule
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.services.MyItmoService
import dev.alllexey.itmowidgets.backend.services.SportUpdateLogService
import dev.alllexey.itmowidgets.backend.services.SportUpdateService
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import retrofit2.Call
import retrofit2.Response
import api.myitmo.model.sport.SportLesson as ApiSportLesson

@Import(SportUpdateService::class, SportUpdateLogService::class)
class SportUpdateServicePersistenceTest : SportQueuePersistenceTest() {
    @Autowired private lateinit var updater: SportUpdateService
    @MockitoBean private lateinit var myItmoService: MyItmoService
    private val api = mock(MyItmoApi::class.java)
    private var incoming = emptyList<ApiSportLesson>()
    private var failure: Exception? = null
    private var precedingLogId = 0L
    private val logger = LoggerFactory.getLogger(SportUpdateService::class.java) as Logger
    private val capturedLogs = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun prepareFetch() {
        // Ignore logs from slice initialization; every assertion below refers only to this invocation's rows.
        precedingLogId = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM sport_update_logs", Long::class.java)!!
        capturedLogs.start()
        logger.addAppender(capturedLogs)
        `when`(myItmoService.myItmo).thenReturn(MyItmo().apply { api = this@SportUpdateServicePersistenceTest.api })
        @Suppress("UNCHECKED_CAST")
        val call = mock(Call::class.java) { invocation ->
            if (invocation.method.name != "execute") RETURNS_DEFAULTS.answer(invocation) else {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                failure?.let { throw it }
                Response.success(ResultResponse<List<SportSchedule>>().apply {
                    result = listOf(SportSchedule().apply { lessons = incoming })
                })
            }
        } as Call<ResultResponse<List<SportSchedule>>>
        val from = LocalDate.now(clock)
        `when`(api.getSportSchedule(from, from.plusDays(21), null, null, null)).thenReturn(call)
    }

    @AfterEach
    fun removeInvocationLogs() {
        // This slice has no background scheduler; the only new log IDs belong to this synthetic test invocation.
        jdbc.update("DELETE FROM sport_update_logs WHERE id > ?", precedingLogId)
        logger.detachAppender(capturedLogs)
        capturedLogs.stop()
    }

    @Test
    fun `database failure rolls back all catalog changes but independently commits a typed failed log`() {
        val existing = lesson()
        val rejected = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val previousStart = jdbc.queryForObject("SELECT starts_at FROM sport_lessons WHERE id=?", OffsetDateTime::class.java, existing)!!.toInstant()
        incoming = listOf(wire(existing, start.plusMinutes(20)), wire(rejected, start))
        val constraint = "test_catalog_failure_$rejected"
        jdbc.execute("ALTER TABLE sport_lessons ADD CONSTRAINT $constraint CHECK (id <> $rejected) NOT VALID")
        try {
            updater.checkLessonUpdates()

            assertEquals(previousStart, jdbc.queryForObject("SELECT starts_at FROM sport_lessons WHERE id=?", OffsetDateTime::class.java, existing)!!.toInstant())
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, rejected))
            assertFailureLog("PERSISTENCE", 2)
            verifyNoInteractions(fcm)
        } finally {
            jdbc.execute("ALTER TABLE sport_lessons DROP CONSTRAINT IF EXISTS $constraint")
        }
    }

    @Test
    fun `upstream failed log survives a caller rollback without retaining its message`() {
        failure = IOException(SECRET)

        assertFailsWith<IllegalStateException> {
            inTransaction {
                updater.checkLessonUpdates()
                error("Synthetic caller rollback")
            }
        }

        assertFailureLog("NETWORK", 0)
        assertSafeApplicationLogs()
        verifyNoInteractions(fcm)
    }

    @Test
    fun `unavailable log storage rolls back catalog and does not manufacture a success record`() {
        val existing = lesson()
        val added = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        incoming = listOf(wire(existing, start.plusMinutes(20)), wire(added, start))
        val constraint = "test_rejected_log_$added"
        jdbc.execute("ALTER TABLE sport_update_logs ADD CONSTRAINT $constraint CHECK (false) NOT VALID")
        try {
            updater.checkLessonUpdates()

            assertEquals(start.toInstant(), jdbc.queryForObject("SELECT starts_at FROM sport_lessons WHERE id=?", OffsetDateTime::class.java, existing)!!.toInstant())
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, added))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs WHERE id>?", Long::class.java, precedingLogId))
            assertTrue(capturedLogs.list.any { it.formattedMessage.contains("failure log unavailable") })
            assertSafeApplicationLogs()
            verifyNoInteractions(fcm)
        } finally {
            jdbc.execute("ALTER TABLE sport_update_logs DROP CONSTRAINT IF EXISTS $constraint")
        }
    }

    private fun assertFailureLog(category: String, received: Int) {
        val rows = jdbc.query("""
            SELECT outcome,error_category,duration_millis,received_lessons,new_lessons_added,
                updated_lessons,skipped_lessons,update_timestamp
            FROM sport_update_logs WHERE id > ?
        """.trimIndent(), { rs, _ -> StoredLog(
            rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
            rs.getObject(8, OffsetDateTime::class.java).toInstant(),
        ) }, precedingLogId)
        val log = rows.single()
        assertEquals("FAILED", log.outcome)
        assertEquals(category, log.category)
        assertEquals(received, log.received)
        assertEquals(0, log.inserted)
        assertEquals(0, log.updated)
        assertEquals(0, log.skipped)
        assertEquals(clock.instant(), log.timestamp)
        assertTrue(log.duration >= 0)
        val storedJson = jdbc.queryForObject("SELECT row_to_json(l)::text FROM sport_update_logs l WHERE id>?", String::class.java, precedingLogId)!!
        assertFalse(storedJson.contains(SECRET))
    }

    private fun assertSafeApplicationLogs() {
        assertTrue(capturedLogs.list.isNotEmpty())
        capturedLogs.list.forEach {
            assertFalse(it.formattedMessage.contains(SECRET))
            assertTrue(it.throwableProxy == null)
        }
    }

    private fun wire(id: Long, start: OffsetDateTime) = ApiSportLesson().apply {
        this.id = id
        sectionId = 980001; sectionName = "Synthetic section"; sectionLevel = 1; lessonLevel = 1; typeId = 1
        timeSlotId = 980001; buildingId = 980001; teacherIsu = 980001; roomId = 10; roomName = "Synthetic room"
        date = start; dateEnd = start.plusHours(1); available = 0
    }

    private data class StoredLog(
        val outcome: String, val category: String?, val duration: Long, val received: Int,
        val inserted: Int, val updated: Int, val skipped: Int, val timestamp: Instant,
    )

    companion object {
        private const val SECRET = "synthetic-private-refresh-payload"
    }
}
