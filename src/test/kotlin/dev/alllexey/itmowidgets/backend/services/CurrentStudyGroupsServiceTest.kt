package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.core.model.GroupData
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CurrentStudyGroupsServiceTest {
    private val clock = MutableClock()
    private val source = FakeSource()
    private val service = CurrentStudyGroupsService(source, clock)

    @Test fun `official groups replace history without changing identity or viewer capabilities`() {
        val input = profile(100001)
        source.value = listOf(OfficialStudyGroup("NEW", 2, "Synthetic faculty"))
        val result = service.profile(input)
        assertEquals(listOf(GroupData("NEW", 2, "SYN")), result.user.groups)
        assertEquals(input.copy(user = input.user.copy(groups = result.user.groups)), result)
        assertEquals(2, input.user.groups.size)
    }

    @Test fun `all current programs survive even when their course is lower than historical ones`() {
        source.value = listOf(OfficialStudyGroup("NEW", 1, "Synthetic faculty"),
            OfficialStudyGroup("PARALLEL", 2, "Another faculty"))
        val result = service.userData(profile(100001).user)
        assertEquals(listOf(GroupData("NEW", 1, "SYN"), GroupData("PARALLEL", 2, "Another faculty")), result.groups)
    }

    @Test fun `an explicitly empty official list is cached and does not fall back to historical groups`() {
        source.value = emptyList()
        assertEquals(emptyList(), service.profile(profile(100001)).user.groups)
        assertEquals(emptyList(), service.profile(profile(100001)).user.groups)
        assertEquals(listOf(100001), source.calls)
    }

    @Test fun `cache stores only groups and expires with injected time`() {
        source.value = listOf(OfficialStudyGroup("NEW", 2, "Faculty"))
        val first = profile(100001)
        service.profile(first)
        val changedViewer = first.copy(relationship = RelationshipState.FRIENDS,
            user = first.user.copy(capabilities = UserCapabilities(true, true, false)))
        val result = service.profile(changedViewer)
        assertEquals(changedViewer.relationship, result.relationship)
        assertEquals(changedViewer.user.capabilities, result.user.capabilities)
        assertEquals(1, source.calls.size)
        clock.advance(Duration.ofMinutes(30))
        source.value = listOf(OfficialStudyGroup("NEXT", 3, "Faculty"))
        assertEquals("NEXT", service.profile(first).user.groups.single().name)
        assertEquals(2, source.calls.size)
    }

    @Test fun `outage retains the last good snapshot including empty and limits retries across a list`() {
        for (good in listOf(emptyList(), listOf(OfficialStudyGroup("NEW", 2, "Faculty")))) {
            val clock = MutableClock()
            val source = FakeSource().apply { value = good }
            val service = CurrentStudyGroupsService(source, clock)
            val input = profile(100001)
            val expected = service.profile(input)
            clock.advance(Duration.ofMinutes(30))
            source.failure = StudyGroupsUnavailable(temporary = true)
            assertEquals(expected, service.profile(input))
            assertEquals(profile(100002), service.profile(profile(100002)))
            assertEquals(2, source.calls.size)
            clock.advance(Duration.ofSeconds(30))
            source.failure = null
            service.profile(input)
            assertEquals(3, source.calls.size)
        }
    }

    @Test fun `a person-specific failure does not suppress reads for other people`() {
        source.failure = StudyGroupsUnavailable(temporary = false)
        val input = profile(100001)
        assertEquals(input, service.profile(input))
        assertEquals(input, service.profile(input))
        source.failure = null
        source.value = emptyList()
        assertTrue(service.profile(profile(100002)).user.groups.isEmpty())
        assertEquals(listOf(100001, 100002), source.calls)
    }

    @Test fun `directory resolution is refused inside a database transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try { assertFailsWith<IllegalStateException> { service.profile(profile(100001)) } }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false) }
        assertTrue(source.calls.isEmpty())
    }

    @Test fun `simultaneous requests share one lookup but retain separate viewer permissions`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val service = CurrentStudyGroupsService(OfficialStudyGroupsSource {
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf(OfficialStudyGroup("NEW", 2, "Faculty"))
        }, clock)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<UserProfile> { service.profile(profile(100001)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = profile(100001).copy(relationship = RelationshipState.FRIENDS)
            val second = pool.submit<UserProfile> { service.profile(other) }
            release.countDown()
            assertEquals(RelationshipState.NONE, first.get(5, TimeUnit.SECONDS).relationship)
            assertEquals(RelationshipState.FRIENDS, second.get(5, TimeUnit.SECONDS).relationship)
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `cache is bounded and preserves list order`() {
        source.value = emptyList()
        val inputs = (1..4097).map(::profile)
        assertEquals(inputs.map { it.user.isu }, service.profiles(inputs).map { it.user.isu })
        service.profile(profile(4097))
        assertEquals(4097, source.calls.size)
        service.profile(profile(1))
        assertEquals(4098, source.calls.size)
    }

    private fun profile(isu: Int) = UserProfile(UserData(isu, "Synthetic student", null,
        listOf(GroupData("OLD", 4, "SYN"), GroupData("NEW", 1, "SYN")),
        UserCapabilities(false, false, true)), RelationshipState.NONE)

    private class FakeSource : OfficialStudyGroupsSource {
        val calls = mutableListOf<Int>()
        var value = emptyList<OfficialStudyGroup>()
        var failure: StudyGroupsUnavailable? = null
        override fun load(isu: Int): List<OfficialStudyGroup> {
            calls += isu
            failure?.let { throw it }
            return value
        }
    }
    private class MutableClock : Clock() {
        private var time = Instant.parse("2026-09-17T00:00:00Z")
        override fun instant() = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(duration: Duration) { time = time.plus(duration) }
    }
}
