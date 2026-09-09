package dev.alllexey.itmowidgets.backend.exceptions

import java.sql.SQLException
import org.hibernate.exception.ConstraintViolationException

/** Exception messages, SQL details and provider payloads are not safe diagnostic fields. */
object SafeDiagnostics {
    private val expectedUniqueConstraints = setOf(
        "uq_auto_sign_not_cancelled", "uq_free_sign_not_cancelled",
        "uq_devices_fcm_token", "uq_friend_requests_direction", "uq_users_isu",
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
