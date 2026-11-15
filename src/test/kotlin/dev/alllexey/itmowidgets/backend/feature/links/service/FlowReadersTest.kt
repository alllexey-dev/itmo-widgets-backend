package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokMemberRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewVerificationKick
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.springframework.beans.factory.ObjectProvider
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Concurrent reads of a flow ISU has not been asked about: no database, the readers meet in the test doubles. */
class FlowReadersTest {
    private val users = mock(UserRepository::class.java, withSettings().stubOnly())
    private val clock = MutableClock()
    private val kick = CountingKick()
    private val lookup = KickLookup(kick)
    private val readers = FlowReaders(
        users,
        mock(IsuPotokRepository::class.java, withSettings().stubOnly()),
        mock(IsuPotokMemberRepository::class.java, withSettings().stubOnly()),
        lookup,
        clock,
    )
    private val pool = Executors.newFixedThreadPool(READERS)

    @AfterEach
    fun stop() {
        lookup.release.countDown()
        pool.shutdownNow()
    }

    @Test
    fun `a read arriving in the middle of another read's request waits for the same fetch`() {
        given(users.findIsuById(MEMBER)).willReturn(MEMBER_ISU)
        given(users.findIsuById(OUTSIDER)).willReturn(OUTSIDER_ISU)
        lookup.holdFirst = true

        val held = read(MEMBER, FLOW)
        assertTrue(lookup.held.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val member = read(MEMBER, FLOW)
        val outsider = read(OUTSIDER, FLOW)
        kick.await()
        lookup.release.countDown()
        readers.fetched(FLOW, setOf(MEMBER_ISU))

        assertTrue(member.result(), "The read that met a request in progress")
        assertTrue(held.result(), "The read whose request was in progress")
        assertFalse(outsider.result())
        assertEquals(1, kick.count.get())
        assertEquals(listOf(FLOW), readers.take(10))
    }

    @Test
    fun `concurrent first reads of a flow share one fetch`() {
        given(users.findIsuById(MEMBER)).willReturn(MEMBER_ISU)

        repeat(ROUNDS) { round ->
            val flow = FLOW + round
            val start = CyclicBarrier(READERS)
            val reads = List(READERS) {
                CompletableFuture.supplyAsync(
                    {
                        start.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        readers.isMember(MEMBER, flow)
                    },
                    pool,
                )
            }
            kick.await()
            readers.fetched(flow, setOf(MEMBER_ISU))

            reads.forEach { assertTrue(it.result(), "Round $round") }
            assertEquals(round + 1, kick.count.get(), "One run is asked for per flow")
            assertEquals(listOf(flow), readers.take(10))
        }
    }

    @Test
    fun `a request after the retry period replaces the fetch nobody answered`() {
        given(users.findIsuById(MEMBER)).willReturn(MEMBER_ISU)

        val unanswered = read(MEMBER, FLOW)
        kick.await()
        clock.now = clock.now.plus(FlowReaders.RETRY)
        val later = read(MEMBER, FLOW)
        kick.await()

        assertFalse(unanswered.result(), "The replaced fetch answers no at once")
        readers.fetched(FLOW, setOf(MEMBER_ISU))
        assertTrue(later.result())
        assertEquals(listOf(FLOW), readers.take(10))
    }

    private fun read(userId: UUID, flowId: Long): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({ readers.isMember(userId, flowId) }, pool)

    /** Under [FlowReaders.FIRST_READ_WAIT], so a read that waits out its fetch fails the test instead of answering no. */
    private fun CompletableFuture<Boolean>.result(): Boolean = get(WAIT_SECONDS, TimeUnit.SECONDS)

    private class CountingKick : ReviewVerificationKick {
        val count = AtomicInteger()
        private val kicked = Semaphore(0)

        override fun kick() {
            count.incrementAndGet()
            kicked.release()
        }

        fun await() = assertTrue(kicked.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS), "No ISU run was asked for")
    }

    /** Hands out [kick]; with [holdFirst] the first lookup stops until [release], holding its read inside the request. */
    private class KickLookup(private val kick: ReviewVerificationKick) : ObjectProvider<ReviewVerificationKick> {
        @Volatile var holdFirst = false
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val lookups = AtomicInteger()

        override fun getIfAvailable(): ReviewVerificationKick {
            if (lookups.getAndIncrement() == 0 && holdFirst) {
                held.countDown()
                release.await()
            }
            return kick
        }
    }

    private companion object {
        val MEMBER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OUTSIDER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        const val MEMBER_ISU = 967401
        const val OUTSIDER_ISU = 967402
        const val FLOW = 93800L
        const val READERS = 4
        const val ROUNDS = 300
        const val WAIT_SECONDS = 3L
    }
}
