package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportSectionOccurrenceRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The order of one lesson's auto-sign queue between section and manual entries (docs/contracts/sport-automation.md,
 * Sections). It receives the queue in FIFO order (`createdAt`, then id) and returns the order attempts, transfers and
 * the positions users see follow; it keeps every element exactly once.
 */
interface SportAutoQueueOrder {
    fun <T> order(queue: List<T>, entryId: (T) -> Long): List<T>
}

/** Plain FIFO by entry time: a section entry stands where the moment its occurrence opened puts it. */
class FifoAutoQueueOrder : SportAutoQueueOrder {
    override fun <T> order(queue: List<T>, entryId: (T) -> Long): List<T> = queue
}

/**
 * Section and manual entries take turns, a section entry first, each side in its FIFO order; when one side runs out the
 * other goes on. So manual users get every other attempt however many subscribers queued weeks earlier.
 */
class AlternatingAutoQueueOrder(private val occurrences: SportSectionOccurrenceRepository) : SportAutoQueueOrder {
    override fun <T> order(queue: List<T>, entryId: (T) -> Long): List<T> {
        if (queue.size < 2) return queue
        val sectionIds = occurrences.findQueuedEntryIds(queue.map(entryId))
        if (sectionIds.isEmpty()) return queue
        val (sections, manual) = queue.partition { entryId(it) in sectionIds }
        return alternate(sections, manual)
    }

    companion object {
        /** [first] and [second] interleaved, [first] leading; the longer one's rest follows. */
        fun <T> alternate(first: List<T>, second: List<T>): List<T> = buildList(first.size + second.size) {
            for (index in 0 until maxOf(first.size, second.size)) {
                first.getOrNull(index)?.let(::add)
                second.getOrNull(index)?.let(::add)
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
class SportAutoQueueOrderConfig {
    /** `itmowidgets.sport.sections.queue-order`: `alternating` (default) or `fifo`. */
    @Bean
    fun sportAutoQueueOrder(config: SportSectionConfig, occurrences: SportSectionOccurrenceRepository): SportAutoQueueOrder =
        when (config.queueOrder) {
            SportSectionConfig.QueueOrder.ALTERNATING -> AlternatingAutoQueueOrder(occurrences)
            SportSectionConfig.QueueOrder.FIFO -> FifoAutoQueueOrder()
        }
}
