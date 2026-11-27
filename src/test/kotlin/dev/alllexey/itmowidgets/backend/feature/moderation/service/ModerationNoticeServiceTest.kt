package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationOutcome
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNotice
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNoticeLink
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNoticeReview
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherDisplayNames
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class ModerationNoticeServiceTest {
    private val store = mock(ModerationNoticeStore::class.java)
    private val names = mock(TeacherDisplayNames::class.java)
    private val devices = mock(DeviceService::class.java)
    private val service = ModerationNoticeService(store, names, devices, ModerationFixture.clock)
    private val now = ModerationFixture.now
    private val author = UUID.randomUUID()

    private val link = ModerationNoticeLink(UUID.randomUUID(), 42, "Математический анализ", "2026-1", "Ведомость", "docs.google.com")
    private val review = ModerationNoticeReview(UUID.randomUUID(), TEACHER, null, "Физика")

    private fun notice(
        outcome: ModerationOutcome,
        note: String? = null,
        link: ModerationNoticeLink? = null,
        review: ModerationNoticeReview? = null,
    ) = ModerationNotice(UUID.randomUUID(), outcome, note, now.minusSeconds(660), link, review)

    @Test
    fun `the list names the teachers of reviews`() {
        val linkNotice = notice(ModerationOutcome.REJECTED, "Не по предмету", link = link)
        val reviewNotice = notice(ModerationOutcome.APPROVED, review = review)
        `when`(store.recent(author, now.minus(Duration.ofDays(30)), now, 50)).thenReturn(listOf(linkNotice, reviewNotice))
        `when`(names.of(setOf(TEACHER))).thenReturn(mapOf(TEACHER to "Преподаватель Тестовый"))

        val listed = service.recent(author)

        assertEquals(linkNotice, listed[0])
        assertEquals("Преподаватель Тестовый", listed[1].review?.teacherName)
    }

    @Test
    fun `a tick pushes each due notice until the batch runs short, then purges old rows`() {
        val first = notice(ModerationOutcome.REJECTED, "Не по предмету", link = link)
        val stale = now.minus(ModerationNoticeService.PUSH_LIFETIME)
        `when`(store.claimDue(now, stale, ModerationNoticeService.BATCH))
            .thenReturn(ModerationNoticeStore.Claimed(ModerationNoticeService.BATCH, listOf(DueNotice(author, now, first))))
            .thenReturn(ModerationNoticeStore.Claimed(0, emptyList()))

        service.tick()

        val alert = ArgumentCaptor.forClass(PushAlert::class.java)
        verify(devices).sendTypedMessageToUser(
            same(author),
            same(ModerationNotice.TYPE),
            same(first),
            same(now.plus(ModerationNoticeService.PUSH_LIFETIME)),
            alert.capture() ?: placeholder,
        )
        assertEquals("notification_moderation_link_rejected", alert.value.titleLocKey)
        verify(store, times(2)).claimDue(now, stale, ModerationNoticeService.BATCH)
        verify(store).purge(now.minus(ModerationNoticeService.RETENTION))
    }

    @Test
    fun `a failing push does not stop the others`() {
        val first = notice(ModerationOutcome.HIDDEN, link = link)
        val second = notice(ModerationOutcome.RESTORED, link = link)
        `when`(store.claimDue(anyInstant(), anyInstant(), anyInt()))
            .thenReturn(ModerationNoticeStore.Claimed(2, listOf(DueNotice(author, now, first), DueNotice(author, now, second))))
        doThrow(IllegalStateException("boom")).`when`(devices)
            .sendTypedMessageToUser(same(author), anyType(), same(first), anyInstant(), anyAlert())

        service.tick()

        verify(devices).sendTypedMessageToUser(same(author), anyType(), same(second), anyInstant(), anyAlert())
    }

    @Test
    fun `the alert names the kind and outcome in its title and the subject in its body`() {
        val rejected = notice(ModerationOutcome.REJECTED, "Не по предмету", link = link)
        val rejectedAlert = ModerationNoticeService.alert(rejected)
        assertEquals("notification_moderation_link_rejected", rejectedAlert.titleLocKey)
        assertEquals("notification_moderation_link_body_note", rejectedAlert.locKey)
        assertEquals(listOf("Ведомость", "Математический анализ", "Не по предмету"), rejectedAlert.locArgs)
        assertEquals("moderation-${rejected.id}", rejectedAlert.collapseId)
        assertEquals("moderation", rejectedAlert.threadId)
        assertEquals(PushAlert.InterruptionLevel.ACTIVE, rejectedAlert.interruptionLevel)

        val untitled = ModerationNoticeService.alert(notice(ModerationOutcome.APPROVED, link = link.copy(title = " ")))
        assertEquals("notification_moderation_link_approved", untitled.titleLocKey)
        assertEquals("notification_moderation_link_body", untitled.locKey)
        assertEquals(listOf("docs.google.com", "Математический анализ"), untitled.locArgs)

        val named = ModerationNoticeService.alert(notice(ModerationOutcome.HIDDEN, review = review.copy(teacherName = "Преподаватель")))
        assertEquals("notification_moderation_review_hidden", named.titleLocKey)
        assertEquals("notification_moderation_review_body", named.locKey)
        assertEquals(listOf("Преподаватель"), named.locArgs)

        val unnamed = ModerationNoticeService.alert(notice(ModerationOutcome.REJECTED, "Оскорбления", review = review))
        assertEquals("notification_moderation_review_rejected", unnamed.titleLocKey)
        assertEquals("notification_moderation_review_body_note", unnamed.locKey)
        assertEquals(listOf("Физика", "Оскорбления"), unnamed.locArgs)

        val nameless = review.copy(subjectTitle = null)
        val noted = ModerationNoticeService.alert(notice(ModerationOutcome.REJECTED, "Оскорбления", review = nameless))
        assertEquals("notification_moderation_review_note", noted.locKey)
        assertEquals(listOf("Оскорбления"), noted.locArgs)
        val bare = ModerationNoticeService.alert(notice(ModerationOutcome.APPROVED, review = nameless))
        assertEquals("notification_moderation_review_unnamed", bare.locKey)
        assertEquals(emptyList(), bare.locArgs)

        assertEquals(
            "notification_moderation_review_restored",
            ModerationNoticeService.alert(notice(ModerationOutcome.RESTORED, review = review)).titleLocKey,
        )
    }

    // Mockito's matchers return null, which Kotlin's non-null parameters refuse: register the matcher, return a value.
    private fun <T : Any> same(value: T): T = eq(value) ?: value

    private fun anyType(): String = anyString() ?: ""

    private fun anyInstant(): java.time.Instant = any(java.time.Instant::class.java) ?: now

    private fun anyAlert(): PushAlert = any(PushAlert::class.java) ?: placeholder

    private val placeholder = PushAlert("t", "k", emptyList(), "c", "t", PushAlert.InterruptionLevel.ACTIVE)

    private companion object {
        const val TEACHER = 142415
    }
}
