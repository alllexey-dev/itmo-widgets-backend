package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.services.IsuPotokCache
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** The cache commits on its own, so this class runs without a test transaction and cleans up after itself. */
@Import(IsuPotokCache::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IsuPotokCachePersistenceTest @Autowired constructor(
    private val cache: IsuPotokCache,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @AfterEach
    fun cleanup() {
        jdbc.update("DELETE FROM isu_potoks")
    }

    @Test
    fun `teachers stay fresh for thirty days and members for one day`() {
        assertNull(cache.teachers(FLOW, NOW))
        assertNull(cache.isMember(FLOW, MEMBER, NOW))

        cache.saveTeachers(FLOW, setOf(142415, 471029), NOW)
        cache.saveMembers(FLOW, setOf(MEMBER, MEMBER + 1), NOW)

        assertEquals(setOf(142415, 471029), cache.teachers(FLOW, NOW.plus(Duration.ofDays(30))))
        assertNull(cache.teachers(FLOW, NOW.plus(Duration.ofDays(30)).plusSeconds(1)))
        assertEquals(true, cache.isMember(FLOW, MEMBER, NOW.plus(Duration.ofDays(1))))
        assertEquals(false, cache.isMember(FLOW, MEMBER + 2, NOW))
        assertNull(cache.isMember(FLOW, MEMBER, NOW.plus(Duration.ofDays(1)).plusSeconds(1)))
        assertNull(cache.isMember(96388, MEMBER, NOW))
    }

    @Test
    fun `saving a list replaces the previous one and keeps the other list`() {
        cache.saveTeachers(FLOW, setOf(142415), NOW)
        cache.saveMembers(FLOW, setOf(MEMBER), NOW)

        cache.saveTeachers(FLOW, setOf(471029), NOW.plusSeconds(60))
        cache.saveMembers(FLOW, emptySet(), NOW.plusSeconds(60))

        assertEquals(setOf(471029), cache.teachers(FLOW, NOW.plusSeconds(60)))
        assertEquals(false, cache.isMember(FLOW, MEMBER, NOW.plusSeconds(60)))
        assertEquals(listOf(471029), jdbc.queryForList("SELECT teacher_isu FROM isu_potok_teachers WHERE potok_id = ?",
            Int::class.javaObjectType, FLOW))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM isu_potok_members WHERE potok_id = ?", Long::class.javaObjectType, FLOW))
    }

    @Test
    fun `purge drops stale lists and empty flows but never fresh data`() {
        cache.saveTeachers(1, setOf(142415), NOW)
        cache.saveMembers(1, setOf(MEMBER), NOW)
        cache.saveTeachers(2, setOf(142415), NOW.minus(Duration.ofDays(2)))
        cache.saveMembers(2, setOf(MEMBER), NOW.minus(Duration.ofDays(2)))
        cache.saveTeachers(3, setOf(142415), NOW.minus(Duration.ofDays(31)))
        cache.saveMembers(4, setOf(MEMBER), NOW.minus(Duration.ofHours(2)))
        cache.saveMembers(5, setOf(MEMBER), NOW.minus(Duration.ofDays(3)))

        cache.purge(NOW)

        assertEquals(listOf(1L, 2L, 4L), jdbc.queryForList("SELECT potok_id FROM isu_potoks ORDER BY potok_id", Long::class.javaObjectType))
        assertEquals(setOf(142415), cache.teachers(1, NOW))
        assertEquals(true, cache.isMember(1, MEMBER, NOW))
        assertEquals(setOf(142415), cache.teachers(2, NOW))
        assertNull(cache.isMember(2, MEMBER, NOW))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM isu_potok_members WHERE potok_id = 2", Long::class.javaObjectType))
        assertEquals(true, cache.isMember(4, MEMBER, NOW))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM isu_potok_teachers WHERE potok_id = 3", Long::class.javaObjectType))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        const val FLOW = 93724L
        const val MEMBER = 966201
    }
}
