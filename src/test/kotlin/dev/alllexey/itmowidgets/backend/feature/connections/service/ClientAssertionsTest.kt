package dev.alllexey.itmowidgets.backend.feature.connections.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientKey
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionClientRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidClientException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.testing.TestClients
import dev.alllexey.itmowidgets.backend.testing.TestKeys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.security.KeyPair
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Each check of a client assertion on its own, against a recorded set of used ids. */
class ClientAssertionsTest {
    private val used = mutableSetOf<Pair<String, String>>()
    private val expiries = mutableListOf<Instant>()
    private val repository = mock(ConnectionClientRepository::class.java) { invocation ->
        when (invocation.method.name) {
            "useAssertion" -> {
                expiries += invocation.getArgument<Instant>(2)
                used.add(invocation.getArgument<String>(0) to invocation.getArgument(1))
            }

            else -> null
        }
    }
    private val assertions = ClientAssertions(repository, ConnectionsConfig(), Clock.fixed(NOW, ZoneOffset.UTC))
    private val rotated = TestKeys.random()
    private val client = TestClients.SERVICE.copy(keys = TestClients.SERVICE.keys + ClientKey("2026-12", TestKeys.publicKeyText(rotated)))
    private val logger = LoggerFactory.getLogger(ClientAssertions::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(logs)
    }

    @Test
    fun `a valid assertion passes once, with either key, and its id is kept past its end`() {
        assertions.require(client, assertion())
        assertEquals(listOf(Instant.ofEpochSecond(NOW.epochSecond + 50 + 30)), expiries)
        assertions.require(client, assertion(jti = "second", kid = "2026-12", key = rotated))
        // At the edges of the skew and the lifetime, an audience among several.
        assertions.require(client, assertion(jti = "early", iat = NOW.epochSecond + 30, exp = NOW.epochSecond + 90))
        assertions.require(client, assertion(jti = "late", iat = NOW.epochSecond - 90, exp = NOW.epochSecond - 30))
        assertions.require(client, assertion(jti = "list", aud = listOf("https://other.example", AUDIENCE)))
        assertions.require(client, assertion(jti = "x".repeat(128)))
        assertEquals(6, used.size)
    }

    @Test
    fun `every broken check is 401 invalid_client and the log names it without the token`() {
        val replayed = assertion(jti = "replayed")
        assertions.require(client, replayed)
        val cases = mapOf(
            "missing" to null,
            "malformed" to "not.a.token",
            "alg" to assertion(alg = "ES256"),
            "no kid" to assertion(kid = null),
            "unknown kid" to assertion(kid = "2025-01"),
            "signature" to assertion(key = TestKeys.random()),
            "signature, the right key of another kid" to assertion(kid = "2026-12"),
            "iss" to assertion(iss = "itmo-mcp"),
            "sub" to assertion(sub = "itmo-mcp"),
            "aud" to assertion(aud = "https://dev.widgets.alllexey.dev"),
            "aud list" to assertion(aud = listOf("https://other.example")),
            "aud number" to assertion(aud = 1),
            "jti empty" to assertion(jti = ""),
            "jti too long" to assertion(jti = "x".repeat(129)),
            "jti number" to assertion(jti = 5),
            "too long" to assertion(exp = NOW.epochSecond + 61),
            "exp before iat" to assertion(iat = NOW.epochSecond, exp = NOW.epochSecond),
            "iat text" to assertion(iat = "1"),
            "expired" to assertion(iat = NOW.epochSecond - 91, exp = NOW.epochSecond - 31),
            "not yet valid" to assertion(iat = NOW.epochSecond + 31, exp = NOW.epochSecond + 60),
            "replayed" to replayed,
            "too large" to assertion(jti = "y".repeat(128), extra = "z".repeat(4096)),
        )
        for ((case, token) in cases) {
            val error = assertFailsWith<InvalidClientException>(case) { assertions.require(client, token) }
            assertEquals("Client authentication failed", error.message)
        }
        assertEquals(cases.size, logs.list.size)
        val logged = logs.list.joinToString("\n") { it.formattedMessage }
        cases.values.filterNotNull().forEach { assertFalse(logged.contains(it.substringAfterLast('.')), "Logs must not carry a token") }
        assertEquals(setOf(client.id to "replayed"), used)
    }

    @Test
    fun `a public client sends none, a confidential one without keys never passes`() {
        assertions.require(TestClients.MCP, null)
        assertFailsWith<InvalidRequestDataException> { assertions.require(TestClients.MCP, assertion()) }
        val keyless = client.copy(keys = emptyList())
        assertFailsWith<InvalidClientException> { assertions.require(keyless, assertion()) }
        assertFailsWith<InvalidClientException> { assertions.require(keyless, null) }
        assertEquals(ClientAuth.CONFIDENTIAL, keyless.auth)
    }

    private fun assertion(
        jti: Any = "a-${System.nanoTime()}",
        kid: String? = "2026-11",
        key: KeyPair = TestClients.SERVICE_KEY,
        alg: String = "EdDSA",
        iss: String = client.id,
        sub: String = client.id,
        aud: Any = AUDIENCE,
        iat: Any = NOW.epochSecond - 10,
        exp: Any = NOW.epochSecond + 50,
        extra: String? = null,
    ): String {
        val header = linkedMapOf<String, Any>("alg" to alg, "typ" to "JWT")
        kid?.let { header["kid"] = it }
        val claims = linkedMapOf("iss" to iss, "sub" to sub, "aud" to aud, "jti" to jti, "iat" to iat, "exp" to exp)
        extra?.let { claims["extra"] = it }
        return EdDsaJws.sign(header, claims, key.private)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-28T09:00:00Z")
        const val AUDIENCE = "https://widgets.alllexey.dev"
    }
}
