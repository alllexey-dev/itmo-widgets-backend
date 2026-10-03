package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialReplaced
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Checks whether the teacher of a review taught its author: some ISU flow has the teacher in its schedule
 * and the author among its members. Runs one at a time on `isuExecutor`, outside transactions, over the
 * `verification_due_at` queue. Without an ISU session reviews wait; ISU never rejects a review.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IsuVerificationService(
    private val client: IsuClient,
    private val credentials: ServiceCredentialStore,
    private val cache: IsuPotokCache,
    private val store: ReviewVerificationStore,
    private val config: IsuConfig,
    @Qualifier("isuExecutor") private val executor: TaskExecutor,
    private val clock: Clock,
) : ReviewVerificationKick, ApplicationListener<ApplicationReadyEvent> {
    @Volatile
    private var session: IsuSession? = null

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        credentials.initializeFromBootstrap(ISU, config.keycloakIdentity)
        kick()
    }

    /** A kick during a run is dropped: the run rereads the queue before it ends. */
    override fun kick() {
        try {
            executor.execute(::run)
        } catch (_: TaskRejectedException) {
            // Already running.
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    fun scheduledKick() = kick()

    @Scheduled(cron = "0 40 4 * * *", zone = "Europe/Moscow")
    fun maintenance() {
        try {
            executor.execute(::maintain)
        } catch (_: TaskRejectedException) {
            logger.info("ISU maintenance skipped: verification is running")
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCredentialReplaced(event: ServiceCredentialReplaced) {
        if (event.credential == ISU) onIdentityReplaced()
    }

    /** A new cookie drops the session and queues every pending review now. */
    fun onIdentityReplaced() {
        session = null
        store.rescheduleAllPending(clock.instant())
        kick()
    }

    /** One pass over the due queue; a seeded or replaced cookie is checked by a login even without reviews. */
    fun run() {
        val run = Run()
        try {
            execute(run)
        } catch (error: Exception) {
            logger.error("ISU verification failed: {}", SafeDiagnostics.describe(error), error)
        } finally {
            if (run.checked + run.postponed + run.requests > 0) {
                logger.info("ISU verification checked={} verified={} unverified={} postponed={} requests={}",
                    run.checked, run.verified, run.unverified, run.postponed, run.requests)
            }
        }
    }

    private fun execute(run: Run) {
        var batch = store.due(clock.instant(), BATCH)
        val state = credentials.state(ISU)
        val probe = state.present && state.status == ServiceCredentialStatus.UNKNOWN
        if (batch.isEmpty() && !probe) return
        if (probe) session = null
        if (!ensureSession(run)) return
        val done = HashSet<Pair<UUID, Instant>>()
        while (true) {
            val fresh = batch.filter { done.add(it.reviewId to it.dueAt) }
            if (fresh.isEmpty()) break
            for (task in fresh) {
                if (!verify(task, run)) return
            }
            batch = store.due(clock.instant(), BATCH)
        }
        if (run.successes > 0) credentials.recordUse(ISU)
    }

    /**
     * False when there is no session: reviews due now wait 6 hours without a cookie or with an expired one
     * and 30 minutes after another login failure, without counting an attempt.
     */
    private fun ensureSession(run: Run): Boolean {
        if (session != null) return true
        val delay = login(run) ?: return true
        run.postponed += store.postponeAllDue(clock.instant(), clock.instant().plus(delay))
        return false
    }

    /** Null on success, otherwise how long due reviews wait. */
    private fun login(run: Run): Duration? {
        val identity = credentials.value(ISU) ?: return UNAVAILABLE_DELAY
        if (credentials.state(ISU).status == ServiceCredentialStatus.EXPIRED) return UNAVAILABLE_DELAY
        return try {
            run.requests++
            val next = client.login(identity)
            credentials.recordRenewal(ISU, next.rotatedIdentity, next.rotatedExpiresAt)
            session = next
            null
        } catch (failure: IsuFailure) {
            credentials.recordFailure(ISU, failure.credentialStatus(), failure.summary())
            logger.warn("ISU login failed: {}", failure.summary())
            if (failure.category == IsuErrorCategory.EXPIRED) UNAVAILABLE_DELAY else FAILURE_DELAY
        }
    }

    /** False when the run must stop. */
    private fun verify(task: VerificationTask, run: Run): Boolean {
        val now = clock.instant()
        run.checked++
        try {
            val today = LocalDate.ofInstant(now, clock.zone)
            for (potok in store.candidates(task)) {
                val teachers = cache.teachers(potok, now)
                    ?: request(run) { client.teachers(it, potok) }.also { cache.saveTeachers(potok, it, now) }
                if (task.teacherIsu !in teachers) continue
                val member = cache.isMember(potok, task.authorIsu, now)
                    ?: request(run) { client.members(it, potok, today) }.also { cache.saveMembers(potok, it, now) }.contains(task.authorIsu)
                if (member) {
                    if (store.markVerified(task, potok, now)) run.verified++
                    return true
                }
            }
            if (store.markUnverified(task, now)) run.unverified++
            return true
        } catch (unavailable: SessionUnavailable) {
            run.postponed += store.postponeAllDue(now, now.plus(unavailable.delay))
            return false
        } catch (failure: IsuFailure) {
            credentials.recordFailure(ISU, ServiceCredentialStatus.FAILED, failure.summary())
            logger.warn("ISU request failed: {}", failure.summary())
            if (store.postpone(task, now.plus(backoff(task.attempts)), countAttempt = true)) run.postponed++
            return failure.category == IsuErrorCategory.MAPPING
        } catch (error: Exception) {
            logger.error("ISU verification of a review failed: {}", SafeDiagnostics.describe(error), error)
            if (store.postpone(task, now.plus(backoff(task.attempts)), countAttempt = true)) run.postponed++
            return false
        }
    }

    /** A lost session gets one new login and one retry; a second loss is a failure of the request. */
    private fun <T> request(run: Run, call: (IsuSession) -> T): T {
        val result = try {
            val current = activeSession(run)
            run.requests++
            call(current)
        } catch (failure: IsuFailure) {
            if (failure.category != IsuErrorCategory.SESSION_LOST) throw failure
            session = null
            val renewed = activeSession(run)
            run.requests++
            call(renewed)
        }
        run.successes++
        return result
    }

    private fun activeSession(run: Run): IsuSession {
        session?.let { return it }
        login(run)?.let { throw SessionUnavailable(it) }
        return checkNotNull(session)
    }

    private fun maintain() {
        try {
            val now = clock.instant()
            cache.purge(now)
            keepAlive(now)
        } catch (error: Exception) {
            logger.error("ISU maintenance failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    /** Logs in when the cookie has not been renewed for a week, so it never expires unused. */
    private fun keepAlive(now: Instant) {
        val state = credentials.state(ISU)
        if (!state.present || state.status == ServiceCredentialStatus.EXPIRED) return
        if (state.lastRenewedAt != null && state.lastRenewedAt > now.minus(KEEP_ALIVE)) return
        session = null
        login(Run())
    }

    private fun backoff(attempts: Int): Duration =
        minOf(MAX_BACKOFF, FAILURE_DELAY.multipliedBy(1L shl minOf(attempts, MAX_DOUBLINGS)))

    private class Run {
        var checked = 0
        var verified = 0
        var unverified = 0
        var postponed = 0
        var requests = 0
        var successes = 0
    }

    private class SessionUnavailable(val delay: Duration) : RuntimeException("ISU session unavailable")

    companion object {
        private val ISU = ServiceCredential.ISU_KEYCLOAK_IDENTITY
        private const val BATCH = 20
        private const val MAX_DOUBLINGS = 6
        val UNAVAILABLE_DELAY: Duration = Duration.ofHours(6)
        val FAILURE_DELAY: Duration = Duration.ofMinutes(30)
        val MAX_BACKOFF: Duration = Duration.ofHours(24)
        val KEEP_ALIVE: Duration = Duration.ofDays(7)
        private val logger = LoggerFactory.getLogger(IsuVerificationService::class.java)
    }
}
