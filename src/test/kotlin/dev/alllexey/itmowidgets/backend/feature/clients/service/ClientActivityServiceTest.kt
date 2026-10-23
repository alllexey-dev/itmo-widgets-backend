package dev.alllexey.itmowidgets.backend.feature.clients.service

import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.persistence.ClientInstanceRepository
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Import(ClientActivityService::class, ClientActivityServiceTest.TimeConfig::class)
class ClientActivityServiceTest @Autowired constructor(
    private val service: ClientActivityService,
    private val instances: ClientInstanceRepository,
    private val clock: MutableClock,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock() = MutableClock()
    }

    private lateinit var user: User
    private lateinit var other: User

    @BeforeEach
    fun reset() {
        clock.now = NOW
        user = em.persistUser(966001, createdAt = NOW)
        other = em.persistUser(966002, createdAt = NOW)
        em.flush()
    }

    @Test
    fun `an instance is written once per hour unless its header changes`() {
        val install = UUID.randomUUID()
        service.report(user.id, ClientKind.ANDROID, install, APP)
        clock.now = NOW.plus(Duration.ofMinutes(59))
        service.report(user.id, ClientKind.ANDROID, install, APP)
        assertEquals(NOW, single().lastSeenAt)

        val updated = APP.copy(version = "2.3.1", build = "20301")
        service.report(user.id, ClientKind.ANDROID, install, updated)
        val row = single()
        assertEquals(listOf("2.3.1", "20301"), listOf(row.version, row.build))
        assertEquals(listOf(NOW, clock.now), listOf(row.firstSeenAt, row.lastSeenAt))
        assertEquals(user.id, row.userId)

        clock.now = clock.now.plus(ClientActivityService.REFRESH)
        service.report(user.id, ClientKind.ANDROID, install, updated)
        assertEquals(clock.now, single().lastSeenAt)
        assertEquals(clock.now, instances.findLastSeen(user.id))
        assertNull(instances.findLastSeen(other.id))
    }

    @Test
    fun `another user cannot take over an instance id`() {
        val install = UUID.randomUUID()
        service.report(user.id, ClientKind.IOS, install, APP.copy(kind = ClientKind.IOS))
        service.report(other.id, ClientKind.IOS, install, APP.copy(kind = ClientKind.IOS, version = "9.9.9"))
        val row = single()
        assertEquals(user.id, row.userId)
        assertEquals("2.3.0", row.version)
    }

    @Test
    fun `the nightly job writes anonymous totals per build group and drops rows unseen for 180 days`() {
        service.report(user.id, ClientKind.ANDROID, UUID.randomUUID(), APP)
        service.report(other.id, ClientKind.ANDROID, UUID.randomUUID(), APP)
        service.report(user.id, ClientKind.ANDROID, UUID.randomUUID(), FORK)
        clock.now = NOW.minus(Duration.ofDays(10))
        service.report(user.id, ClientKind.WEB, UUID.randomUUID(), ClientHeader(ClientKind.WEB, "abc123", channel = "web"))
        clock.now = NOW.minus(ClientActivityService.RETENTION).minusSeconds(1)
        service.report(other.id, ClientKind.AGENT, UUID.randomUUID(), ClientHeader(ClientKind.AGENT, "1.0.0"))
        clock.now = NOW.plus(Duration.ofHours(1))

        service.nightly()

        val days = instances.findDaily(LocalDate.parse("2026-01-01")).map {
            listOf(it.day.toString(), it.kind, it.custom, it.version, it.active1d, it.active7d, it.active30d)
        }
        assertEquals(
            listOf(
                listOf("2026-09-23", "ANDROID", false, "2.3.0", 2L, 2L, 2L),
                listOf("2026-09-23", "ANDROID", true, "2.3.0", 1L, 1L, 1L),
                listOf("2026-09-23", "WEB", false, "abc123", 0L, 0L, 1L),
            ),
            days,
        )
        assertEquals(4, instances.count())
        // A second run the same night replaces the totals.
        service.nightly()
        assertEquals(3, instances.findDaily(LocalDate.parse("2026-01-01")).size)
    }

    private fun single() = instances.findAll().single().also { em.clear() }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val APP = ClientHeader(ClientKind.ANDROID, "2.3.0", "20300", "play", os = "36")
        val FORK = ClientHeader(ClientKind.ANDROID, "2.3.0-mine", "1", "custom", source = "someone/ITMO.Widgets", base = "2.3.0")
    }
}
