package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportPredictionSnapshot
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrence
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStep
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStopReason
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionSubscription
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportLessonRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportSectionOccurrenceRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportSectionSubscriptionRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.UserSportLessonRepository
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAutoSignService.Companion.toOffsetDateTime
import dev.alllexey.itmowidgets.backend.feature.sport.web.MySportSection
import dev.alllexey.itmowidgets.backend.feature.sport.web.QueueEntryStatus
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportSectionDate
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportSectionSlot
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Sections (docs/contracts/sport-automation.md, Sections): auto-sign for the auto-sign. A subscription never books
 * or notifies itself; it creates an ordinary auto entry per occurrence, so limits, forecast matching, transfer,
 * delivery and agents apply unchanged. Every mutation runs under the owner lock, like every queue mutation.
 */
@Service
class SportSectionService(
    private val subscriptions: SportSectionSubscriptionRepository,
    private val occurrences: SportSectionOccurrenceRepository,
    private val autoSign: SportAutoSignService,
    private val autoRepository: SportAutoSignEntryRepository,
    private val freeRepository: SportFreeSignEntryRepository,
    private val bookings: UserSportLessonRepository,
    private val lessons: SportLessonRepository,
    private val users: UserRepository,
    private val config: SportSectionConfig,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getMine(userId: UUID): List<MySportSection> = subscriptions.findActiveByUser(userId).map(::toModel)

    /** Counts and the caller's own place only: never who else chose a slot. */
    @Transactional(readOnly = true)
    fun getSlots(userId: UUID, lessonIds: List<Long>): List<SportSectionSlot> {
        if (lessonIds.size > MAX_SLOT_LOOKUPS) throw InvalidRequestDataException("At most $MAX_SLOT_LOOKUPS lessons per request")
        return lessons.findAllById(lessonIds.distinct()).sortedBy { it.id }.mapNotNull { lesson ->
            val slotKey = SportQueueRules.slotKey(lesson) ?: return@mapNotNull null
            val mine = subscriptions.findActive(userId, slotKey)
            val subscribers = subscriptions.countActive(slotKey)
            val cap = cap(lesson)
            SportSectionSlot(
                lessonId = lesson.id,
                subscribers = subscribers,
                cap = cap,
                full = mine == null && cap != null && subscribers >= cap,
                subscriptionId = mine?.id,
                place = mine?.let(::place),
            )
        }
    }

    @Transactional
    fun subscribe(userId: UUID, lessonId: Long): MySportSection {
        lockOwner(userId)
        val lesson = lessons.findById(lessonId).orElseThrow { NotFoundException("SportLesson not found with ID: $lessonId") }
        val slotKey = SportQueueRules.slotKey(lesson)
            ?: throw BusinessRuleException("A section requires an explicit online room or a known venue and room")
        subscriptions.findActive(userId, slotKey)?.let { return toModel(it) }
        if (!isBooked(userId, lessonId)) throw BusinessRuleException("A section starts from a booked lesson")
        val cap = cap(lesson)
        // The slot's queue grows under each subscriber's own lock: a race at the cap may admit one extra subscriber.
        if (cap != null && subscriptions.countActive(slotKey) >= cap) throw BusinessRuleException("No places left in this section")
        val now = Instant.now(clock)
        val subscription = subscriptions.save(
            SportSectionSubscription(user = users.getReferenceById(userId), slotKey = slotKey, sourceLesson = lesson, createdAt = now),
        )
        open(subscription, lesson, now)
        return toModel(subscription)
    }

    /** Turns one predicted occurrence off or back on; the subscription and its place in the slot's queue stay. */
    @Transactional
    fun setSkipped(userId: UUID, subscriptionId: Long, start: OffsetDateTime, skipped: Boolean): MySportSection {
        lockOwner(userId)
        val subscription = owned(userId, subscriptionId)
        if (!subscription.isActive) throw BusinessRuleException("The section is stopped")
        val source = subscription.sourceLesson
        val offset = Duration.between(source.start, start)
        if (offset <= Duration.ZERO || offset.toMillis() % PERIOD.toMillis() != 0L) {
            throw InvalidRequestDataException("Not an occurrence of this section")
        }
        if (!start.toInstant().isAfter(Instant.now(clock))) throw BusinessRuleException("The occurrence has already started")
        val occurrence = occurrences.find(subscriptionId, start)
        if (skipped) {
            if (occurrence == null) {
                occurrences.save(
                    SportSectionOccurrence(
                        subscription = subscription,
                        start = start,
                        end = start.plus(duration(source)),
                        state = SportSectionOccurrenceState.SKIPPED,
                    ),
                )
            } else if (occurrence.state != SportSectionOccurrenceState.SKIPPED) {
                occurrence.entry?.let { autoSign.cancelEntry(userId, it.id!!) }
                occurrence.state = SportSectionOccurrenceState.SKIPPED
            }
        } else if (occurrence?.state == SportSectionOccurrenceState.SKIPPED) {
            val prototype = lessons.findAllByStart(start.minus(PERIOD)).firstOrNull { SportQueueRules.slotKey(it) == subscription.slotKey }
            // Not opened yet: forget the skip and let the pass queue the date when its prototype is published.
            if (prototype == null) occurrences.delete(occurrence) else decide(occurrence, prototype)
        }
        return toModel(subscription)
    }

    @Transactional
    fun stop(userId: UUID, subscriptionId: Long) {
        lockOwner(userId)
        // Entries already queued stay: they are ordinary queues the user cancels on their own.
        owned(userId, subscriptionId).stop(SportSectionStopReason.USER, Instant.now(clock))
    }

    /**
     * Scheduler discovery after a catalog refresh, in the slots' queue order. A slot's published lesson is the
     * forecast prototype of the occurrence two weeks later, so that occurrence can be queued from this moment on.
     */
    @Transactional(readOnly = true)
    fun pendingSteps(): List<SportSectionStep> {
        val candidates = subscriptions.findActiveCandidates()
        if (candidates.isEmpty()) return emptyList()
        val slots = candidates.mapTo(HashSet()) { it.slotKey }
        val prototypes = lessons.findAllByStartGreaterThanEqualOrderByStartAscIdAsc(OffsetDateTime.now(clock))
            .mapNotNull { lesson -> SportQueueRules.slotKey(lesson)?.takeIf { it in slots }?.let { it to lesson.id } }
            .groupBy({ it.first }, { it.second })
        return candidates.map { SportSectionStep(it.subscriptionId, it.userId, prototypes[it.slotKey].orEmpty()) }
    }

    /** One subscription's pass; an old step cannot undo a stop committed after discovery. */
    @Transactional
    fun process(step: SportSectionStep) {
        lockOwner(step.userId)
        val subscription = subscriptions.findById(step.subscriptionId).orElse(null) ?: return
        if (!subscription.isActive) return
        val now = Instant.now(clock)
        lessons.findAllById(step.prototypeLessonIds).sortedBy { it.start }.forEach { open(subscription, it, now) }
        // Two periods without a published prototype: the slot left the timetable (a term ended, the lesson moved).
        if (!now.isBefore(subscription.lastOpenedEnd.plus(PERIOD).toInstant())) {
            subscription.stop(SportSectionStopReason.EXPIRED, now)
        }
    }

    /** Auto-sign for the occurrence [prototype] forecasts is open: queue it once, unless it is handled already. */
    private fun open(subscription: SportSectionSubscription, prototype: SportLesson, now: Instant) {
        val prediction = SportPredictionSnapshot.fromLesson(prototype)
        subscription.opened(prediction.predictedEnd)
        if (!now.isBefore(prediction.predictedStart.toInstant())) return
        if (occurrences.find(subscription.id!!, prediction.predictedStart) != null) return
        val occurrence = SportSectionOccurrence(
            subscription = subscription,
            start = prediction.predictedStart,
            end = prediction.predictedEnd,
            state = SportSectionOccurrenceState.LIMIT,
        )
        decide(occurrence, prototype)
        occurrences.save(occurrence)
    }

    /** Queues [occurrence] through [prototype]; a full monthly limit skips it and keeps the place in the slot's queue. */
    private fun decide(occurrence: SportSectionOccurrence, prototype: SportLesson) {
        val userId = occurrence.subscription.user.id
        // Adopting the user's own entry keeps one entry per occurrence; createEntry would replace a terminal one.
        val entry = autoRepository.findNotCancelledEntry(userId, prototype.id)
            // Checked first: a refused createEntry would mark this transaction rollback-only.
            ?: if (autoSign.getLimits(userId).available > 0) {
                autoRepository.getReferenceById(autoSign.createEntry(userId, prototype.id).id)
            } else {
                null
            }
        occurrence.entry = entry
        occurrence.state = if (entry == null) SportSectionOccurrenceState.LIMIT else SportSectionOccurrenceState.QUEUED
    }

    private fun owned(userId: UUID, subscriptionId: Long): SportSectionSubscription {
        val subscription = subscriptions.findById(subscriptionId).orElseThrow { NotFoundException("Section $subscriptionId not found") }
        if (subscription.user.id != userId) throw PermissionDeniedException("Section belongs to another user")
        return subscription
    }

    private fun cap(lesson: SportLesson): Int? = lessons.findPlacesLimit(lesson.id)?.let(config::cap)

    private fun place(subscription: SportSectionSubscription): Int =
        subscriptions.countActiveAhead(subscription.slotKey, subscription.createdAt, subscription.id!!) + 1

    /** A stored booking, or a satisfied queue entry the next booking sync has not confirmed yet. */
    private fun isBooked(userId: UUID, lessonId: Long): Boolean = bookings.existsByUserIdAndLessonId(userId, lessonId) ||
        autoRepository.findAllNotCancelledEntriesByRealLesson(userId, lessonId).any { it.status == QueueEntryStatus.SATISFIED } ||
        freeRepository.findNotCancelledEntry(userId, lessonId)?.status == QueueEntryStatus.SATISFIED

    private fun lockOwner(userId: UUID) {
        users.lockById(userId) ?: throw NotFoundException("User not found")
    }

    private fun duration(lesson: SportLesson): Duration = Duration.between(lesson.start, lesson.end)

    private fun toModel(subscription: SportSectionSubscription): MySportSection {
        val now = OffsetDateTime.now(clock)
        val source = subscription.sourceLesson
        val handled = occurrences.findEndingAfter(subscription.id!!, now).associateBy { it.start.toInstant() }
        // The same positions the auto-sign routes show, so a date's place moves as manual users join.
        val queued = handled.values.mapNotNull { it.entry?.id }.takeIf { it.isNotEmpty() }
            ?.let { ids -> autoSign.getUserEntries(subscription.user.id).filter { it.id in ids && it.position > 0 }.associateBy { it.id } }
            .orEmpty()
        // The predicted dates of the slot after the source lesson, from the first one that has not ended.
        val first = generateSequence(source.start.plus(PERIOD)) { it.plus(PERIOD) }.first { it.plus(duration(source)).isAfter(now) }
        val next = generateSequence(first) { it.plus(PERIOD) }.take(SHOWN_OCCURRENCES).map { start ->
            val occurrence = handled[start.toInstant()]
            val entryId = occurrence?.entry?.id?.takeIf { occurrence.state == SportSectionOccurrenceState.QUEUED }
            SportSectionDate(
                start = start,
                end = start.plus(duration(source)),
                state = occurrence?.state,
                entryId = entryId,
                position = queued[entryId]?.position,
                total = queued[entryId]?.total,
            )
        }.toList()
        return MySportSection(
            id = subscription.id!!,
            lesson = source.toDto(),
            occurrences = next,
            place = place(subscription),
            subscribers = subscriptions.countActive(subscription.slotKey),
            createdAt = subscription.createdAt.toOffsetDateTime(),
        )
    }

    private companion object {
        const val MAX_SLOT_LOOKUPS = 50
        const val SHOWN_OCCURRENCES = 3
        val PERIOD: Duration = Duration.ofDays(7 * SportQueueRules.PREDICTION_WEEKS)
    }
}
