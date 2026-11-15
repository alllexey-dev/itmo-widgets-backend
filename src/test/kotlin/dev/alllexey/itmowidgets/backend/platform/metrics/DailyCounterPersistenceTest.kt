package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The additive upsert and the reader's sums on real PostgreSQL, committed. */
@Import(DailyCounterStore::class, DailyCounterReader::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DailyCounterPersistenceTest @Autowired constructor(
    private val store: DailyCounterStore,
    private val reader: DailyCounterReader,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @AfterEach
    fun cleanup() {
        jdbc.update("DELETE FROM stat_daily WHERE metric IN (?, ?)", METRIC.name, OTHER.name)
    }

    @Test
    fun `flushes add to the stored rows instead of replacing them`() {
        store.add(mapOf(key(DAY, "A", "x") to 2L, key(DAY, "A", "") to 1L))
        store.add(mapOf(key(DAY, "A", "x") to 3L))
        store.add(emptyMap())

        assertEquals(
            listOf(mapOf("dim1" to "A", "dim2" to "", "value" to 1L), mapOf("dim1" to "A", "dim2" to "x", "value" to 5L)),
            jdbc.queryForList("SELECT dim1, dim2, value FROM stat_daily WHERE metric = ? ORDER BY dim2", METRIC.name),
        )
    }

    @Test
    fun `a write with a bad row adds nothing, so the flusher may retry it whole`() {
        assertFailsWith<Exception> { store.add(mapOf(key(DAY, "A", "x") to 1L, key(DAY, "B", "x".repeat(65)) to 1L)) }

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stat_daily WHERE metric = ?", Int::class.java, METRIC.name))
    }

    @Test
    fun `the reader sums one metric over the days of a period by day and dim2 and by both labels`() {
        store.add(
            mapOf(
                key(DAY.minusDays(1), "A", "x") to 100L,
                key(DAY, "A", "x") to 1L,
                key(DAY, "A", "y") to 2L,
                key(DAY, "B", "x") to 4L,
                key(DAY.plusDays(1), "A", "x") to 8L,
                key(DAY.plusDays(2), "B", "y") to 1000L,
                DailyCounterKey(DAY, OTHER, "A", "x") to 10_000L,
            ),
        )
        val period = StatsPeriod(DAY, DAY.plusDays(1))

        assertEquals(
            mapOf(DAY to mapOf("x" to 5L, "y" to 2L), DAY.plusDays(1) to mapOf("x" to 8L)),
            reader.byDayAndDim2(METRIC, period),
        )
        assertEquals(mapOf("A" to mapOf("x" to 9L, "y" to 2L), "B" to mapOf("x" to 4L)), reader.byDim1AndDim2(METRIC, period))
        assertEquals(mapOf(DAY to mapOf("x" to 5L, "y" to 2L)), reader.byDayAndDim2(METRIC, StatsPeriod(DAY, DAY)))
        assertEquals(emptyMap(), reader.byDim1AndDim2(METRIC, StatsPeriod(DAY.plusDays(10), DAY.plusDays(20))))
    }

    @Test
    fun `the first day is the earliest of any metric`() {
        store.add(mapOf(key(EARLIEST.plusDays(3), "A", "") to 1L, DailyCounterKey(EARLIEST, OTHER, "A", "") to 1L))

        assertEquals(EARLIEST, reader.firstDay())
    }

    private fun key(day: LocalDate, dim1: String, dim2: String) = DailyCounterKey(day, METRIC, dim1, dim2)

    private companion object {
        val METRIC = DailyMetric("test_reader")
        val OTHER = DailyMetric("test_reader_other")
        val DAY: LocalDate = LocalDate.parse("2026-11-10")

        /** Before any counter a real run could write in this container. */
        val EARLIEST: LocalDate = LocalDate.parse("2000-01-01")
    }
}
