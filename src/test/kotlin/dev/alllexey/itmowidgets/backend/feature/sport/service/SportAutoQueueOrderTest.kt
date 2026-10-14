package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportSectionOccurrenceRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class SportAutoQueueOrderTest {
    private val occurrences = mock(SportSectionOccurrenceRepository::class.java)
    private val alternating = AlternatingAutoQueueOrder(occurrences)

    private fun sections(vararg ids: Long) = `when`(occurrences.findQueuedEntryIds(anyCollection())).thenReturn(ids.toSet())

    @Test
    fun `sections and manual entries take turns, a section first, each side in its own order`() {
        sections(1, 2, 3)

        assertEquals(listOf(1L, 10L, 2L, 3L), alternating.order(listOf(1L, 2L, 3L, 10L)) { it })
    }

    @Test
    fun `the longer side goes on when the other runs out`() {
        sections(5)

        assertEquals(listOf(5L, 1L, 2L, 3L), alternating.order(listOf(1L, 2L, 3L, 5L)) { it })
    }

    @Test
    fun `a queue of one side keeps its FIFO order`() {
        sections()
        assertEquals(listOf(3L, 1L, 2L), alternating.order(listOf(3L, 1L, 2L)) { it })
        sections(3, 1, 2)
        assertEquals(listOf(3L, 1L, 2L), alternating.order(listOf(3L, 1L, 2L)) { it })
    }

    @Test
    fun `fifo keeps the queue as it is`() {
        assertEquals(listOf(2L, 1L), FifoAutoQueueOrder().order(listOf(2L, 1L)) { it })
    }
}
