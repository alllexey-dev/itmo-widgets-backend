package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.services.*
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.aop.framework.AopInfrastructureBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/** Real proxied services and committed synthetic fixtures shared by queue regression tests. */
@Import(
    SportQueuePersistenceTest.QueueConfig::class,
    UserService::class, UserRegistrationService::class, UserPrivacyService::class,
    SportLessonService::class, SportAutoSignService::class, SportFreeSignService::class,
    SportQueueTransitionService::class, SportAutoSignTransferService::class, UserSportLessonService::class,
    SportCatalogService::class, SportAutoSignNotificationService::class, SportFreeSignNotificationService::class,
    SportNotificationDeliveryService::class, DeviceService::class, DeviceDeliveryStore::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class SportQueuePersistenceTest : PostgreSqlRepositoryTest() {
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var clock: QueueTestClock
    @Autowired protected lateinit var autos: SportAutoSignService
    @Autowired protected lateinit var frees: SportFreeSignService
    @Autowired protected lateinit var transitions: SportQueueTransitionService
    @Autowired protected lateinit var transfers: SportAutoSignTransferService
    @Autowired protected lateinit var bookings: UserSportLessonService
    @Autowired protected lateinit var registration: UserRegistrationService
    @Autowired protected lateinit var autoRepository: SportAutoSignEntryRepository
    @Autowired protected lateinit var freeRepository: SportFreeSignEntryRepository
    @Autowired protected lateinit var lessonRepository: SportLessonRepository
    @Autowired protected lateinit var catalog: SportCatalogService
    @Autowired protected lateinit var autoNotifications: SportAutoSignNotificationService
    @Autowired protected lateinit var freeNotifications: SportFreeSignNotificationService
    @Autowired protected lateinit var delivery: SportNotificationDeliveryService
    @Autowired protected lateinit var deviceStore: DeviceDeliveryStore
    @Autowired protected lateinit var lockGate: QueueOwnerLockGate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoBean protected lateinit var fcm: FcmService
    @MockitoBean private lateinit var verifier: ItmoJwtVerifier
    @MockitoBean private lateinit var groups: GroupService
    @MockitoBean private lateinit var friends: FriendService

    private val owners = mutableListOf<UUID>()
    private val lessonIds = mutableListOf<Long>()

    @BeforeEach
    fun resetQueueClock() {
        clock.set(INITIAL_NOW)
        lockGate.reset()
    }

    @AfterEach
    fun removeCommittedQueueFixtures() {
        lockGate.reset()
        for (id in owners) {
            for (table in listOf("sport_auto_sign_entries", "sport_free_sign_entries", "user_sport_lessons", "devices")) {
                jdbc.update("DELETE FROM $table WHERE user_id=?", id)
            }
            jdbc.update("DELETE FROM users WHERE id=?", id)
        }
        for (id in lessonIds) {
            jdbc.update("DELETE FROM sport_update_logs WHERE id IN (SELECT sport_update_log_id FROM sport_update_logs_new_lessons WHERE new_lessons_id=?)", id)
            jdbc.update("DELETE FROM sport_lessons WHERE id=?", id)
        }
    }

    protected fun owner(limit: Int = 3): UUID {
        val id = registration.findOrCreateByIsu(nextIsu.getAndIncrement()).id
        owners.add(id)
        jdbc.update("UPDATE user_settings SET auto_sign_limit=? WHERE user_id=?", limit, id)
        return id
    }

    protected fun reserveLessonId(): Long = nextLesson.getAndIncrement().also(lessonIds::add)

    /** The discovery a scheduler performs: a superset bounded by the horizon, never the rule itself. */
    protected fun expiredFreeCandidates(): List<SportQueueCandidate> =
        freeRepository.findExpiredCandidates(SportQueueRules.freeExpiryHorizon(OffsetDateTime.now(clock)))

    /** The discovery a scheduler performs: derive the lesson's key, then look forecasts up by it. */
    protected fun unresolvedCandidates(lessonId: Long): List<SportQueueCandidate> {
        val lesson = lessonRepository.findById(lessonId).orElseThrow()
        val matchKey = SportQueueRules.matchKey(lesson) ?: return emptyList()
        return autoRepository.findUnresolvedCandidates(matchKey)
    }

    protected fun lesson(
        start: OffsetDateTime = OffsetDateTime.now(clock).plusHours(3),
        end: OffsetDateTime = start.plusHours(1),
        roomId: Long = 10,
        buildingId: Long = 980001,
    ): Long {
        val id = reserveLessonId()
        jdbc.update("INSERT INTO sport_sections(id,name) VALUES (980001,'Synthetic section') ON CONFLICT DO NOTHING")
        jdbc.update("INSERT INTO sport_buildings(id,name) VALUES (?,'Synthetic building') ON CONFLICT DO NOTHING", buildingId)
        jdbc.update("INSERT INTO sport_teachers(isu,name) VALUES (980001,'Synthetic teacher') ON CONFLICT DO NOTHING")
        jdbc.update("INSERT INTO sport_time_slots(id,time_start,time_end) VALUES (980001,'12:00','13:00') ON CONFLICT DO NOTHING")
        jdbc.update("""
            INSERT INTO sport_lessons(id,section_id,section_level,lesson_level,type_id,section_name,time_slot_id,
                building_id,teacher_isu,room_id,room_name,starts_at,ends_at,last_seen_at)
            VALUES (?,980001,1,1,1,'Synthetic section',980001,?,980001,?,'Synthetic room',?,?,?)
        """.trimIndent(), id, buildingId, roomId, start, end, OffsetDateTime.now(clock))
        return id
    }

    protected fun auto(
        owner: UUID,
        prototype: Long,
        status: QueueEntryStatus = QueueEntryStatus.WAITING,
        real: Long? = null,
        attempts: Int = 0,
        maxAttempts: Int = 10,
    ): SportQueueCandidate {
        val id = autos.createEntry(owner, prototype).id
        jdbc.update("""
            UPDATE sport_auto_sign_entries SET status=?,real_lesson_id=?,notification_attempts=?,
                max_notification_attempts=?,first_notified_at=?,last_notified_at=? WHERE id=?
        """.trimIndent(), status.name, real, attempts, maxAttempts,
            if (attempts > 0) OffsetDateTime.now(clock).minusMinutes(16) else null,
            if (attempts > 0) OffsetDateTime.now(clock).minusMinutes(16) else null, id)
        return SportQueueCandidate(id, owner)
    }

    protected fun free(
        owner: UUID,
        lesson: Long,
        force: Boolean = false,
        status: QueueEntryStatus = QueueEntryStatus.WAITING,
        attempts: Int = 0,
        maxAttempts: Int = 10,
    ): SportQueueCandidate {
        val id = frees.createEntry(owner, lesson, force).id
        jdbc.update("""
            UPDATE sport_free_sign_entries SET status=?,notification_attempts=?,max_notification_attempts=?,
                first_notified_at=?,last_notified_at=? WHERE id=?
        """.trimIndent(), status.name, attempts, maxAttempts,
            if (attempts > 0) OffsetDateTime.now(clock).minusMinutes(16) else null,
            if (attempts > 0) OffsetDateTime.now(clock).minusMinutes(16) else null, id)
        return SportQueueCandidate(id, owner)
    }

    protected fun inTransaction(action: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { action() }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class QueueConfig {
        @Bean fun queueTestClock() = QueueTestClock()
        @Bean fun queueOwnerLockGate(jdbc: JdbcTemplate) = QueueOwnerLockGate(jdbc)

        @Bean
        @Primary
        fun gatedUsers(
            @Qualifier("userRepository") delegate: UserRepository,
            gate: QueueOwnerLockGate,
        ): UserRepository = object : UserRepository by delegate, AopInfrastructureBean {
            // The delegate already is Spring Data's transactional proxy. Do not CGLIB-proxy this test decorator.
            override fun lockById(id: UUID): UUID? = gate.lock(id) { delegate.lockById(id) }
        }
    }

    companion object {
        val INITIAL_NOW: Instant = Instant.parse("2026-09-21T06:00:00Z")
        private val nextIsu = AtomicInteger(980001)
        private val nextLesson = AtomicLong(980001)
    }
}

class QueueTestClock : Clock() {
    private val current = AtomicReference(SportQueuePersistenceTest.INITIAL_NOW)
    override fun instant(): Instant = current.get()
    override fun getZone(): ZoneId = ZoneId.of("Europe/Moscow")
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    fun set(now: Instant) { current.set(now) }
    fun advance(duration: Duration) { current.updateAndGet { it.plus(duration) } }
}

/** Test-only gate, never a production synchronization hook. All waits are bounded. */
class QueueOwnerLockGate(private val jdbc: JdbcTemplate) {
    @Volatile private var owner: UUID? = null
    private val visits = AtomicInteger()
    private var firstLocked = CountDownLatch(1)
    private var secondAttempt = CountDownLatch(1)
    private var releaseFirst = CountDownLatch(1)
    @Volatile private var firstPid: Int = 0
    @Volatile private var secondPid: Int = 0

    fun arm(id: UUID) {
        reset()
        visits.set(0)
        firstLocked = CountDownLatch(1)
        secondAttempt = CountDownLatch(1)
        releaseFirst = CountDownLatch(1)
        owner = id
    }

    fun lock(id: UUID, action: () -> UUID?): UUID? {
        if (owner != id) return action()
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
            assertTrue(releaseFirst.await(15, TimeUnit.SECONDS), "First owner transaction was not released")
        }
        return result
    }

    fun awaitFirstLocked() { assertTrue(firstLocked.await(10, TimeUnit.SECONDS)) }
    fun awaitSecondAttempt() { assertTrue(secondAttempt.await(10, TimeUnit.SECONDS)) }
    fun awaitSecondBlocked() {
        awaitSecondAttempt()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            if (jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean::class.java, firstPid, secondPid) == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        error("Second transaction did not block on the first owner row")
    }
    fun release() { releaseFirst.countDown() }
    fun reset() { owner = null; release() }
}
