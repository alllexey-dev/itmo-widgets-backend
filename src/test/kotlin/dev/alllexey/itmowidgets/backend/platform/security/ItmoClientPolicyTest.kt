package dev.alllexey.itmowidgets.backend.platform.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItmoClientPolicyTest {
    private val logger = LoggerFactory.getLogger(ItmoClientPolicy::class.java) as Logger
    private lateinit var logs: ListAppender<ILoggingEvent>

    @BeforeEach
    fun attach() {
        logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `log mode accepts every client and counts each value`() {
        val policy = ItmoClientPolicy(ALLOWED, "log")

        repeat(2) { policy.check(token("student-personal-cabinet")) }
        policy.check(token("student-personal-cabinet-dev"))
        policy.check(token("synthetic-other-client"))
        policy.check(token(null))

        assertEquals(
            mapOf(
                ItmoClientPolicy.NONE to 1L,
                "student-personal-cabinet" to 2L,
                "student-personal-cabinet-dev" to 1L,
                "synthetic-other-client" to 1L,
            ),
            policy.drainCounts(),
        )
        assertEquals(emptyMap(), policy.drainCounts(), "Draining restarts the counts")
    }

    @Test
    fun `enforce mode rejects a client outside the allowlist and a token without one`() {
        val policy = ItmoClientPolicy(ALLOWED, "enforce")

        policy.check(token("student-personal-cabinet"))
        assertFailsWith<JWTVerificationException> { policy.check(token("synthetic-other-client")) }
        assertFailsWith<JWTVerificationException> { policy.check(token(null)) }

        assertEquals(
            mapOf(ItmoClientPolicy.NONE to 1L, "student-personal-cabinet" to 1L, "synthetic-other-client" to 1L),
            policy.drainCounts(),
        )
    }

    @Test
    fun `an unknown mode stops startup`() {
        assertFailsWith<IllegalArgumentException> { ItmoClientPolicy(ALLOWED, "warn") }
    }

    @Test
    fun `the hourly line lists client counts and nothing of the tokens`() {
        val policy = ItmoClientPolicy(ALLOWED, "log")
        val token = token("student-personal-cabinet")
        repeat(3) { policy.check(token) }
        policy.check(token("student-personal-cabinet-dev"))

        policy.logCounts()
        policy.logCounts()

        val event = logs.list.single()
        assertEquals(Level.INFO, event.level)
        assertEquals("azp counts: {student-personal-cabinet: 3, student-personal-cabinet-dev: 1}", event.formattedMessage)
        assertFalse(event.formattedMessage.contains(token.token))
        assertFalse(event.formattedMessage.contains(ISU.toString()))
    }

    @Test
    fun `distinct clients beyond the cap share one counter`() {
        val policy = ItmoClientPolicy(ALLOWED, "log")

        (1..40).forEach { policy.check(token("synthetic-client-$it")) }

        val counts = policy.drainCounts()
        assertEquals(40L, counts.values.sum())
        assertTrue(counts.size <= 33, "32 clients and the shared counter")
        assertEquals(8L, counts[ItmoClientPolicy.OTHER])
    }

    private fun token(client: String?): DecodedJWT = JWT.decode(
        JWT.create().withClaim("isu", ISU).apply { if (client != null) withClaim("azp", client) }.sign(Algorithm.none()),
    )

    private companion object {
        const val ISU = 970001
        const val ALLOWED = "student-personal-cabinet, student-personal-cabinet-dev"
    }
}
