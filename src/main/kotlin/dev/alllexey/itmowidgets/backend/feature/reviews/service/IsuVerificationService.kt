package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialReplaced
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowLinkCheck
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowLinkIsuQueue
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowReaders
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
 * and the author among its members. Also checks pending FLOW links: the author is a member of the link's flow
 * and the flow is small. Then fetches the member lists FLOW-link readers wait for ([FlowReaders]). Runs one at a
 * time on `isuExecutor`, outside transactions, over the `verification_due_at` queue of reviews, then the
 * `isu_check_due_at` queue of link revisions, then the readers' queue. Without an ISU session the queues wait and
 * the readers' one is dropped; ISU never rejects a review or a link.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IsuVerificationService(
    private val client: IsuClient,
    private val credentials: ServiceCredentialStore,
    private val cache: IsuPotokCache,
    private val store: ReviewVerificationStore,
    private val links: FlowLinkIsuQueue,
    private val readers: FlowReaders,
    private val config: IsuConfig,
    @Qualifier("isuExecutor") private val executor: TaskExecutor,
    private val clock: Clock,
) : ReviewVerificationKick,
    ApplicationListener<ApplicationReadyEvent> {
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

    /** A new cookie drops the session and queues every pending review and waiting link check now. */
    fun onIdentityReplaced() {
        session = null
        store.rescheduleAllPending(clock.instant())
        links.rescheduleAllPending(clock.instant())
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
                logger.info(
                    "ISU verification checked={} verified={} unverified={} linksApproved={} linksHeld={} postponed={} requests={}",
                    run.checked,
                    run.verified,
                    run.unverified,
                    run.linksApproved,
                    run.linksHeld,
                    run.postponed,
                    run.requests,
                )
            }
        }
    }

    private fun execute(run: Run) {
        val now = clock.instant()
        val state = credentials.state(ISU)
        val probe = state.present && state.status == ServiceCredentialStatus.UNKNOWN
        if (store.due(now, 1).isEmpty() && links.due(now, 1).isEmpty() && !readers.hasWanted() && !probe) return
        if (probe) session = null
        if (!ensureSession(run)) return
        if (!drain({ store.due(clock.instant(), BATCH) }, { it.reviewId to it.dueAt }) { verify(it, run) }) return
        if (!drain({ links.due(clock.instant(), BATCH) }, { it.revisionId to it.dueAt }) { checkLink(it, run) }) return
        if (!refreshReaders(run)) return
        if (run.successes > 0) credentials.recordUse(ISU)
    }

    /** Checks each due time of a task once until the queue has nothing new; false when the run must stop. */
    private fun <T> drain(due: () -> List<T>, key: (T) -> Pair<UUID, Instant>, check: (T) -> Boolean): Boolean {
        val done = HashSet<Pair<UUID, Instant>>()
        while (true) {
            val fresh = due().filter { done.add(key(it)) }
            if (fresh.isEmpty()) return true
            for (task in fresh) {
                if (!check(task)) return false
            }
        }
    }

    /**
     * False when there is no session: reviews due now wait 6 hours without a cookie or with an expired one
     * and 30 minutes after another login failure, without counting an attempt.
     */
    private fun ensureSession(run: Run): Boolean {
        if (session != null) return true
        val delay = login(run) ?: return true
        run.postponed += postponeAllDue(clock.instant(), clock.instant().plus(delay))
        return false
    }

    private fun postponeAllDue(now: Instant, until: Instant): Int {
        readers.dropWanted()
        return store.postponeAllDue(now, until) + links.postponeAllDue(now, until)
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
    private fun verify(task: VerificationTask, run: Run): Boolean =
        attempt(run, "a review", task.attempts, { store.postpone(task, it, countAttempt = true) }) { now ->
            val today = LocalDate.ofInstant(now, clock.zone)
            for (potok in store.candidates(task)) {
                val teachers = cache.teachers(potok, now)
                    ?: request(run) { client.teachers(it, potok) }.also { cache.saveTeachers(potok, it, now) }
                if (task.teacherIsu !in teachers) continue
                val member = cache.isMember(potok, task.authorIsu, now)
                    ?: request(run) { client.members(it, potok, today) }.also { cache.saveMembers(potok, it, now) }.contains(task.authorIsu)
                if (member) {
                    if (store.markVerified(task, potok, now)) run.verified++
                    return@attempt
                }
            }
            if (store.markUnverified(task, now)) run.unverified++
        }

    /**
     * Approves the link when ISU lists its author among at most `flow-link-max-members` members of its flow;
     * otherwise, an unknown flow (no members) included, the link waits for a moderator. False when the run must stop.
     */
    private fun checkLink(task: FlowLinkCheck, run: Run): Boolean =
        attempt(run, "a flow link", task.attempts, { links.postpone(task, it) }) { now ->
            val members = members(run, task.flowId, now)
            if (task.authorIsu in members && members.size <= config.flowLinkMaxMembers) {
                if (links.approve(task, members.size)) run.linksApproved++
            } else if (links.leaveToModerators(task)) {
                run.linksHeld++
            }
        }

    /** Fetches the queued member lists, each once; a failed one waits for the next read. False when the run must stop. */
    private fun refreshReaders(run: Run): Boolean {
        while (true) {
            val flows = readers.take(BATCH)
            if (flows.isEmpty()) return true
            for (flowId in flows) {
                if (!attempt(run, "flow members", 0, { false }) { now -> members(run, flowId, now) }) return false
            }
        }
    }

    private fun members(run: Run, flowId: Long, now: Instant): Set<Int> = cache.members(flowId, now)
        ?: request(run) { client.members(it, flowId, LocalDate.ofInstant(now, clock.zone)) }.also { cache.saveMembers(flowId, it, now) }

    /**
     * Runs one check. Without a session every due task waits; a failed request moves only this task with an attempt
     * by [backoff]. False when the run must stop.
     */
    private fun attempt(run: Run, what: String, attempts: Int, postpone: (Instant) -> Boolean, check: (Instant) -> Unit): Boolean {
        val now = clock.instant()
        run.checked++
        try {
            check(now)
            return true
        } catch (unavailable: SessionUnavailable) {
            run.postponed += postponeAllDue(now, now.plus(unavailable.delay))
            return false
        } catch (failure: IsuFailure) {
            credentials.recordFailure(ISU, ServiceCredentialStatus.FAILED, failure.summary())
            logger.warn("ISU request failed: {}", failure.summary())
            if (postpone(now.plus(backoff(attempts)))) run.postponed++
            return failure.category == IsuErrorCategory.MAPPING
        } catch (error: Exception) {
            logger.error("ISU verification of {} failed: {}", what, SafeDiagnostics.describe(error), error)
            if (postpone(now.plus(backoff(attempts)))) run.postponed++
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

    private fun backoff(attempts: Int): Duration = minOf(MAX_BACKOFF, FAILURE_DELAY.multipliedBy(1L shl minOf(attempts, MAX_DOUBLINGS)))

    private class Run {
        var checked = 0
        var verified = 0
        var unverified = 0
        var linksApproved = 0
        var linksHeld = 0
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
