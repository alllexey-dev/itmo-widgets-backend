package dev.alllexey.itmowidgets.backend.repositories

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.configs.RetentionConfig
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.model.UserSportLesson
import dev.alllexey.itmowidgets.backend.services.TechnicalLogRetentionService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** Global retention runs only in its own disposable schema, with committed seeds and real batch transactions. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(
    classes = [PostgreSqlRepositoryTest.PersistenceConfig::class, SportUpdateLogRetentionTest.FixtureConfig::class],
    initializers = [SportUpdateLogRetentionTest.IsolatedDatabase::class],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportUpdateLogRetentionTest {
    @Autowired private lateinit var retention: TechnicalLogRetentionService
    @Autowired private lateinit var logs: SportUpdateLogRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @PersistenceContext private lateinit var entityManager: EntityManager

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetentionConfig::class)
    @Import(TechnicalLogRetentionService::class)
    class FixtureConfig {
        @Bean fun retentionClock(): Clock = CLOCK
    }

    class IsolatedDatabase : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            val postgres = PostgreSqlTestDatabase.container
            val schema = "retention_" + UUID.randomUUID().toString().replace("-", "")
            val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
            val url = "${postgres.jdbcUrl}${separator}currentSchema=$schema"
            // Neither inherited environment nor a separate Flyway/Hikari URL may select any other database.
            context.environment.propertySources.addFirst(MapPropertySource("isolated-retention-fixture", mapOf(
                "spring.datasource.url" to url,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.datasource.hikari.jdbc-url" to url,
                "spring.datasource.hikari.username" to postgres.username,
                "spring.datasource.hikari.password" to postgres.password,
                "spring.flyway.url" to url,
                "spring.flyway.user" to postgres.username,
                "spring.flyway.password" to postgres.password,
                "spring.flyway.schemas" to schema,
                "spring.flyway.default-schema" to schema,
                "spring.jpa.properties.hibernate.default_schema" to schema,
                "itmowidgets.retention.sport-update-log-days" to 90,
                "itmowidgets.retention.batch-size" to 1,
            )))
        }
    }

    @BeforeEach
    fun clearTechnicalLogs() {
        // This dedicated schema is not shared with any other repository suite.
        jdbc.update("DELETE FROM sport_update_logs")
    }

    @Test
    fun `empty history completes without mutations`() {
        assertEquals(0L, retention.cleanupSportUpdateLogs())
        assertEquals(emptyList(), logIds())
    }

    @Test
    fun `strict 90 day cutoff preserves exact boundary and newer timestamps`() {
        val expired = insertLog(CUTOFF.minus(1, ChronoUnit.MICROS))
        val boundary = insertLog(CUTOFF)
        val newer = insertLog(CUTOFF.plus(1, ChronoUnit.MICROS))

        assertEquals(1L, retention.cleanupSportUpdateLogs())

        assertEquals(listOf(boundary, newer), logIds())
        assertFalse(logs.existsById(expired))
        assertEquals(0L, retention.cleanupSportUpdateLogs())
    }

    @Test
    fun `public repository batch is bounded and orders oldest timestamp then identity`() {
        val newest = insertLog(CUTOFF.minusSeconds(1))
        val earlierTie = insertLog(CUTOFF.minusSeconds(3))
        val laterTie = insertLog(CUTOFF.minusSeconds(3))
        val oldest = insertLog(CUTOFF.minusSeconds(5))
        val middle = insertLog(CUTOFF.minusSeconds(2))

        assertEquals(2, logs.deleteBatchBefore(CUTOFF, 2))

        assertEquals(listOf(newest, laterTie, middle), logIds())
        assertFalse(logs.existsById(earlierTie))
        assertFalse(logs.existsById(oldest))
        assertEquals(3, logs.deleteBatchBefore(CUTOFF, 10))
        assertEquals(emptyList(), logIds())
    }

    @Test
    fun `daily run stops at 100 batches and subsequent runs finish the backlog`() {
        val expired = (1..101).map { insertLog(CUTOFF.minusSeconds(1)) }
        val fresh = insertLog(NOW)

        assertEquals(100L, retention.cleanupSportUpdateLogs())
        assertEquals(listOf(expired.last(), fresh), logIds())
        assertEquals(1L, retention.cleanupSportUpdateLogs())
        assertEquals(listOf(fresh), logIds())
        assertEquals(0L, retention.cleanupSportUpdateLogs())
    }

    @Test
    fun `expired log links cascade but catalog queues bookings users and settings remain identical`() {
        seedUserHistory()
        val expired = insertLog(CUTOFF.minusSeconds(1), addedLessons = 1)
        val fresh = insertLog(NOW, addedLessons = 1)
        jdbc.update("INSERT INTO sport_update_logs_new_lessons VALUES (?, ?)", expired, 9101L)
        jdbc.update("INSERT INTO sport_update_logs_new_lessons VALUES (?, ?)", fresh, 9102L)
        val before = preservedTables.associateWith(::rows)
        val freshLog = jdbc.queryForObject("SELECT to_jsonb(l)::text FROM sport_update_logs l WHERE id=?", String::class.java, fresh)

        assertEquals(1L, retention.cleanupSportUpdateLogs())

        assertEquals(before, preservedTables.associateWith(::rows))
        assertEquals(listOf(fresh), logIds())
        assertEquals(freshLog, jdbc.queryForObject("SELECT to_jsonb(l)::text FROM sport_update_logs l WHERE id=?", String::class.java, fresh))
        assertEquals(listOf(Link(fresh, 9102L)), jdbc.query(
            "SELECT sport_update_log_id, new_lessons_id FROM sport_update_logs_new_lessons ORDER BY sport_update_log_id",
            { row, _ -> Link(row.getLong(1), row.getLong(2)) },
        ))
    }

    @Test
    fun `cleanup batches remain committed when its caller transaction rolls back`() {
        insertLog(CUTOFF.minusSeconds(1))

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            jdbc.update("INSERT INTO sport_buildings (id, name) VALUES (9999, 'Caller rollback fixture')")
            assertEquals(1L, retention.cleanupSportUpdateLogs())
            transaction.setRollbackOnly()
        }

        assertEquals(emptyList(), logIds())
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_buildings WHERE id=9999", Long::class.java))
    }

    @Test
    fun `a later database failure preserves committed batches and emits only safe diagnostics`() {
        val first = insertLog(CUTOFF.minusSeconds(3))
        val blocked = insertLog(CUTOFF.minusSeconds(2))
        val last = insertLog(CUTOFF.minusSeconds(1))
        val logger = LoggerFactory.getLogger(TechnicalLogRetentionService::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        jdbc.execute("""
            CREATE FUNCTION reject_retention_fixture() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF OLD.id = $blocked THEN
                    RAISE EXCEPTION 'synthetic-retention-secret' USING ERRCODE = '45000';
                END IF;
                RETURN OLD;
            END;
            ${'$'}${'$'}
        """)
        jdbc.execute("CREATE TRIGGER reject_retention_fixture BEFORE DELETE ON sport_update_logs FOR EACH ROW EXECUTE FUNCTION reject_retention_fixture()")
        logger.addAppender(events)
        try {
            assertEquals(1L, retention.cleanupSportUpdateLogs())
            assertFalse(logs.existsById(first))
            assertEquals(listOf(blocked, last), logIds())
            val event = events.list.single()
            assertTrue(event.formattedMessage.contains("sqlState=45000"))
            assertFalse(event.formattedMessage.contains("synthetic-retention-secret"))
            assertNotNull(event.throwableProxy)
        } finally {
            logger.detachAppender(events)
            events.stop()
            jdbc.execute("DROP TRIGGER reject_retention_fixture ON sport_update_logs")
            jdbc.execute("DROP FUNCTION reject_retention_fixture()")
        }
        assertEquals(2L, retention.cleanupSportUpdateLogs())
        assertEquals(emptyList(), logIds())
    }

    private fun insertLog(timestamp: Instant, addedLessons: Int = 0): Long = jdbc.queryForObject(
        """
            INSERT INTO sport_update_logs (
                update_timestamp, outcome, duration_millis, received_lessons,
                new_lessons_added, updated_lessons, skipped_lessons
            ) VALUES (?, 'SUCCESS', 3, ?, ?, 0, 0) RETURNING id
        """,
        Long::class.java, timestamp.atOffset(ZoneOffset.UTC), addedLessons, addedLessons,
    )!!

    private fun logIds(): List<Long> = jdbc.queryForList("SELECT id FROM sport_update_logs ORDER BY id", Long::class.java)

    private fun rows(table: String): List<String> = jdbc.queryForList(
        "SELECT to_jsonb(entry)::text FROM $table entry ORDER BY to_jsonb(entry)::text", String::class.java,
    )

    private fun seedUserHistory() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            val section = SportSection(9001, "Retention section")
            val building = SportBuilding(9001, "Retention building")
            val teacher = SportTeacher(9001, "Retention teacher")
            val slot = SportTimeSlot(9001, "10:00", "11:00")
            listOf(section, building, teacher, slot).forEach(entityManager::persist)
            val start = OffsetDateTime.ofInstant(CUTOFF.minus(30, ChronoUnit.DAYS), ZoneOffset.UTC)
            fun lesson(id: Long) = SportLesson(
                id, section, 1, 1, 1, "Retention section", slot, building.id, teacher,
                1, "Retention room", start, start.plusHours(1), NOW,
            ).also(entityManager::persist)
            val prototype = lesson(9101)
            val actual = lesson(9102)
            val owner = User(isu = 920301, pictureUrl = null, name = "Retention fixture owner", createdAt = start.toInstant())
            owner.settings = UserSettingsEntity(
                user = owner, autoSignLimit = 7,
                scheduleVisibility = SharingVisibility.NOBODY, sportVisibility = SharingVisibility.ALL,
            )
            entityManager.persist(owner)
            entityManager.persist(SportFreeSignEntity(user = owner, lesson = prototype, forceSign = false, createdAt = start.toInstant()))
            entityManager.persist(SportAutoSignEntity(user = owner, prototypeLesson = prototype, realLesson = actual, createdAt = start.toInstant()))
            entityManager.persist(UserSportLesson(user = owner, lesson = prototype, createdAt = start.toInstant()))
        }
    }

    private data class Link(val logId: Long, val lessonId: Long)

    companion object {
        private val NOW = Instant.parse("2026-09-09T01:15:00Z")
        private val CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Moscow"))
        private val CUTOFF = NOW.minus(90, ChronoUnit.DAYS)
        private val preservedTables = listOf(
            "sport_lessons", "sport_sections", "sport_buildings", "sport_teachers", "sport_time_slots",
            "sport_auto_sign_entries", "sport_free_sign_entries", "user_sport_lessons", "users", "user_settings",
        )
    }
}
