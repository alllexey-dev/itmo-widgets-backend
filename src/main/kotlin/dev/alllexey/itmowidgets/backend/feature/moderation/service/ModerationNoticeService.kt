package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationOutcome
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNotice
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.service.PushLocKeys
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherDisplayNames
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The author's moderation notices (docs/contracts/notifications.md#moderation-notices): the bell's list, and a push
 * for each notice once it is due, from a minute tick. Teachers are named outside the read transactions, FCM is called
 * outside any; logs carry counts and exception classes only.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModerationNoticeService(
    private val store: ModerationNoticeStore,
    private val teacherNames: TeacherDisplayNames,
    private val devices: DeviceService,
    private val clock: Clock,
) {
    /** The caller's notices due in the last [RETENTION], newest first. */
    fun recent(userId: UUID): List<ModerationNotice> {
        val now = clock.instant()
        return named(store.recent(userId, now.minus(RETENTION), now, LIST_LIMIT))
    }

    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = TICK_MILLIS)
    fun tick() {
        try {
            var claimed: Int
            do {
                val now = clock.instant()
                val batch = store.claimDue(now, now.minus(PUSH_LIFETIME), BATCH)
                claimed = batch.count
                val notices = named(batch.pushes.map { it.notice })
                batch.pushes.zip(notices).forEach { (due, notice) -> push(due.recipientId, notice, due.dueAt.plus(PUSH_LIFETIME)) }
            } while (claimed == BATCH)
            store.purge(clock.instant().minus(RETENTION))
        } catch (error: Exception) {
            logger.error("Moderation notices tick failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    private fun push(recipientId: UUID, notice: ModerationNotice, expiresAt: Instant) {
        try {
            devices.sendTypedMessageToUser(recipientId, ModerationNotice.TYPE, notice, expiresAt, alert(notice))
        } catch (error: Exception) {
            logger.warn("Moderation notice push failed: {}", error.javaClass.simpleName)
        }
    }

    /** Names the teachers of review notices; a name that cannot be found stays null. */
    private fun named(notices: List<ModerationNotice>): List<ModerationNotice> {
        val isus = notices.mapNotNull { it.review?.teacherIsu }.toSet()
        if (isus.isEmpty()) return notices
        val names = teacherNames.of(isus)
        return notices.map { notice ->
            val review = notice.review ?: return@map notice
            notice.copy(review = review.copy(teacherName = names[review.teacherIsu]))
        }
    }

    companion object {
        const val TICK_MILLIS = 60_000L
        const val BATCH = 100
        const val LIST_LIMIT = 50

        /** How long the bell lists a notice; older rows are deleted. */
        val RETENTION: Duration = Duration.ofDays(30)

        /** A notice older than this is no longer news for a push; the bell still lists it. */
        val PUSH_LIFETIME: Duration = Duration.ofDays(3)

        private val logger = LoggerFactory.getLogger(ModerationNoticeService::class.java)

        /**
         * The iOS alert. Android ignores it and renders from `data`. The title names the kind and the outcome; the
         * body names the link (its title, else its host, and the subject) or the review (the teacher, else its
         * subject, else a generic line), and a rejection adds the moderator's note.
         */
        fun alert(notice: ModerationNotice): PushAlert {
            val link = notice.link
            val note = notice.note?.takeIf { notice.outcome == ModerationOutcome.REJECTED }
            val (locKey, args) = if (link != null) {
                val title = link.title?.trim()?.takeIf { it.isNotEmpty() } ?: link.host
                if (note != null) {
                    PushLocKeys.MODERATION_LINK_BODY_NOTE to listOf(title, link.subjectName, note)
                } else {
                    PushLocKeys.MODERATION_LINK_BODY to listOf(title, link.subjectName)
                }
            } else {
                val name = notice.review?.let { it.teacherName ?: it.subjectTitle }?.trim()?.takeIf { it.isNotEmpty() }
                when {
                    name != null && note != null -> PushLocKeys.MODERATION_REVIEW_BODY_NOTE to listOf(name, note)
                    name != null -> PushLocKeys.MODERATION_REVIEW_BODY to listOf(name)
                    note != null -> PushLocKeys.MODERATION_REVIEW_NOTE to listOf(note)
                    else -> PushLocKeys.MODERATION_REVIEW_UNNAMED to emptyList()
                }
            }
            return PushAlert(
                titleLocKey = PushLocKeys.moderationTitle(review = link == null, notice.outcome.name),
                locKey = locKey,
                locArgs = args,
                collapseId = "moderation-${notice.id}",
                threadId = "moderation",
                interruptionLevel = PushAlert.InterruptionLevel.ACTIVE,
            )
        }
    }
}
