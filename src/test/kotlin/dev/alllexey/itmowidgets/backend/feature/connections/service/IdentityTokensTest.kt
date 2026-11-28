package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.web.Jwk
import dev.alllexey.itmowidgets.backend.feature.connections.web.Jwks
import dev.alllexey.itmowidgets.backend.testing.TestKeys
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityTokensTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-28T09:00:00Z"), ZoneOffset.UTC)
    private val json = JsonMapper.builder().build()

    @Test
    fun `without a key identity is off and the JWKS is empty`() {
        for (key in listOf("", "  ")) {
            val tokens = IdentityTokens(ConnectionsConfig(identity = ConnectionsConfig.Identity(privateKey = key)), clock)
            assertFalse(tokens.available)
            assertEquals(Jwks(emptyList()), tokens.jwks())
            assertFailsWith<IllegalStateException> { tokens.issue("c", 1, null, UUID.randomUUID()) }
        }
        assertEquals("Identity(privateKey=none, keyId=identity-1)", ConnectionsConfig().identity.toString())
    }

    @Test
    fun `a malformed key stops the start without showing it`() {
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private
        for (key in listOf("not base64!", Base64.getEncoder().encodeToString(ByteArray(48)), TestKeys.privateKeyText(rsa))) {
            val error = assertFailsWith<IllegalStateException> {
                IdentityTokens(ConnectionsConfig(identity = ConnectionsConfig.Identity(privateKey = key)), clock)
            }
            assertFalse(error.message.orEmpty().contains(key))
        }
        assertFailsWith<IllegalArgumentException> { ConnectionsConfig(identity = ConnectionsConfig.Identity(keyId = "bad kid")) }
    }

    @Test
    fun `a token carries exactly its claims and verifies against the JWKS`() {
        val pair = TestKeys.random()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + TestKeys.privateKeyText(pair.private).chunked(40).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"
        val config = ConnectionsConfig(identity = ConnectionsConfig.Identity(privateKey = pem, keyId = "2026-11"))
        assertTrue(config.toString().contains("privateKey=set"))
        val tokens = IdentityTokens(config, clock)
        assertTrue(tokens.available)
        assertEquals(Jwks(listOf(Jwk(x = TestKeys.publicKeyText(pair), kid = "2026-11"))), tokens.jwks())

        val connection = UUID.randomUUID()
        val jti = UUID.randomUUID()
        val token = tokens.issue("synthetic-service", 466123, "Тестов Тест", connection, jti)
        val parsed = assertNotNull(EdDsaJws.parse(token, 4096))
        val key = assertNotNull(EdDsaJws.decode(tokens.jwks().keys.single().x)?.let(EdDsaJws::publicKey))
        assertTrue(parsed.verifiedBy(key))
        assertEquals("""{"alg":"EdDSA","kid":"2026-11","typ":"JWT"}""", json.writeValueAsString(parsed.header))
        val iat = clock.instant().epochSecond
        assertEquals(
            """{"iss":"https://widgets.alllexey.dev","aud":"synthetic-service","sub":"466123","isu":466123,""" +
                """"name":"Тестов Тест","cid":"$connection","jti":"$jti","iat":$iat,"exp":${iat + 300}}""",
            json.writeValueAsString(parsed.claims),
        )
        val nameless = assertNotNull(EdDsaJws.parse(tokens.issue("synthetic-service", 466123, null, connection), 4096)).claims
        assertFalse(nameless.has("name"))
    }
}
