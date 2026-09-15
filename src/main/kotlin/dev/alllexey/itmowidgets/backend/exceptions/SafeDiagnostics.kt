package dev.alllexey.itmowidgets.backend.exceptions

import java.sql.SQLException
import org.hibernate.exception.ConstraintViolationException

/**
 * Exception messages, SQL details and provider payloads are not safe diagnostic fields, so
 * [describe] is the only form allowed to leave the process in an API response.
 *
 * It is not a substitute for a log. Application logs are trusted, and a class name plus a
 * SQLSTATE cannot explain a failure on its own, so a caller logging [describe] passes the
 * throwable as the final argument and lets SLF4J print the cause chain with it. The two
 * routine high-volume paths, a rejected client token and a rejected catalog row, keep a single
 * WARN line and move the chain to DEBUG.
 */
object SafeDiagnostics {
    private val expectedUniqueConstraints = setOf(
        "uq_auto_sign_not_cancelled", "uq_free_sign_not_cancelled",
        "uq_devices_fcm_token", "uq_friendships_pair", "uq_users_isu",
    )

    fun isExpectedUniqueConflict(error: Throwable): Boolean = causes(error).any {
        it is ConstraintViolationException && it.sqlState == "23505" &&
            it.constraintName in expectedUniqueConstraints
    }

    fun describe(error: Throwable): String {
        val sqlState = causes(error).filterIsInstance<SQLException>().firstOrNull()?.sqlState
            ?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }
        val constraint = causes(error).filterIsInstance<ConstraintViolationException>()
            .firstOrNull()?.constraintName?.takeIf { it in expectedUniqueConstraints }
        return buildString {
            append(error.javaClass.simpleName)
            sqlState?.let { append(" sqlState=").append(it) }
            constraint?.let { append(" constraint=").append(it) }
        }
    }

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause }.take(10)
}
