package dev.alllexey.itmowidgets.backend.testing

import org.hibernate.SessionFactory
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

/**
 * The number of JDBC statements [block] prepares, for the rule that a list page takes a fixed number of queries
 * whatever its size. Clears the persistence context afterwards, so the next call reads from the database again.
 */
fun TestEntityManager.statements(block: () -> Unit): Long {
    val statistics = entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    statistics.isStatisticsEnabled = true
    statistics.clear()
    try {
        block()
        return statistics.prepareStatementCount
    } finally {
        statistics.isStatisticsEnabled = false
        clear()
    }
}
