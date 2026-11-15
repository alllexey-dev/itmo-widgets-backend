package dev.alllexey.itmowidgets.backend.platform.metrics

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Date

/** Writes counts into `stat_daily`. */
@Repository
class DailyCounterStore(private val jdbc: JdbcTemplate) {
    /** Adds every count to its row, creating it; one transaction, so a failed write adds nothing. */
    @Transactional
    fun add(counts: Map<DailyCounterKey, Long>) {
        if (counts.isEmpty()) return
        jdbc.batchUpdate(UPSERT, counts.entries.toList(), counts.size) { statement, (key, value) ->
            statement.setDate(1, Date.valueOf(key.day))
            statement.setString(2, key.metric.name)
            statement.setString(3, key.dim1)
            statement.setString(4, key.dim2)
            statement.setLong(5, value)
        }
    }

    private companion object {
        const val UPSERT = """
            INSERT INTO stat_daily (day, metric, dim1, dim2, value) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (day, metric, dim1, dim2) DO UPDATE SET value = stat_daily.value + excluded.value
        """
    }
}
