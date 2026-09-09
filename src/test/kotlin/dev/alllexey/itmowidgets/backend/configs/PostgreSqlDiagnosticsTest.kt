package dev.alllexey.itmowidgets.backend.configs

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import jakarta.persistence.EntityManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

class PostgreSqlDiagnosticsTest @Autowired constructor(
    private val entityManager: EntityManager,
) : PostgreSqlRepositoryTest() {
    @Test
    fun `real PostgreSQL failure does not leak private input before or inside HTTP exception handling`() {
        val secret = "synthetic-private-database-value"
        val hibernateLogger = LoggerFactory.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper") as Logger
        val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val driverLogs = ListAppender<ILoggingEvent>().apply { start() }
        val applicationLogs = ListAppender<ILoggingEvent>().apply { start() }
        hibernateLogger.addAppender(driverLogs)
        handlerLogger.addAppender(applicationLogs)
        try {
            val failure = assertFailsWith<RuntimeException> {
                entityManager.createNativeQuery("SELECT CAST(:privateValue AS bigint)")
                    .setParameter("privateValue", secret).singleResult
            }

            val result = GlobalExceptionHandler().handleRuntimeException(failure)

            assertFalse(hibernateLogger.isErrorEnabled)
            assertTrue(driverLogs.list.isEmpty(), "Hibernate must not print the raw PostgreSQL diagnostic")
            assertEquals(500, result.statusCode.value())
            assertEquals("internal_server_error", result.body!!.error!!.code)
            assertFalse(result.body.toString().contains(secret))
            assertTrue(applicationLogs.list.any { it.formattedMessage.contains("sqlState=22P02") })
            applicationLogs.list.forEach { event ->
                assertNull(event.throwableProxy)
                val text = event.formattedMessage + event.message + event.argumentArray.orEmpty().joinToString()
                assertFalse(text.contains(secret))
            }
        } finally {
            hibernateLogger.detachAppender(driverLogs)
            handlerLogger.detachAppender(applicationLogs)
            driverLogs.stop()
            applicationLogs.stop()
        }
    }
}
