package dev.alllexey.itmowidgets.backend.feature.sport.service

import org.springframework.stereotype.Component

/**
 * The order of one lesson's auto-sign queue, the seam for a fairness policy between section and manual entries
 * (vibe/plans/sport-sections-fairness.md in the app repository). It receives the queue in FIFO order (`createdAt`,
 * then id) and returns the order attempts and positions follow; it must keep every element exactly once.
 */
interface SportAutoQueueOrder {
    fun <T> order(queue: List<T>, entryId: (T) -> Long): List<T>
}

/** Plain FIFO by entry time: a section entry stands where the moment its occurrence opened puts it. */
@Component
class FifoAutoQueueOrder : SportAutoQueueOrder {
    override fun <T> order(queue: List<T>, entryId: (T) -> Long): List<T> = queue
}
