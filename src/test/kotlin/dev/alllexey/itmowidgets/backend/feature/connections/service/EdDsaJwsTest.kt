package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.testing.TestKeys
import org.junit.jupiter.api.Test
import java.security.Signature
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdDsaJwsTest {
    @Test
    fun `the public key derived from a private key is the one generated with it`() {
        repeat(5) {
            val pair = TestKeys.random()
            assertContentEquals(EdDsaJws.rawPublicKey(pair.public), EdDsaJws.rawPublicKey(EdDsaJws.publicKeyOf(pair.private)))
            val reread = EdDsaJws.privateKey(pair.private.encoded)
            assertContentEquals(EdDsaJws.rawPublicKey(pair.public), EdDsaJws.rawPublicKey(EdDsaJws.publicKeyOf(reread)))
        }
        // The RFC 8032 test vector 1: this seed has this public key.
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex()
        val private = EdDsaJws.privateKey("302e020100300506032b657004220420".hex() + seed)
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            EdDsaJws.rawPublicKey(EdDsaJws.publicKeyOf(private)).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `a signed token parses and verifies with its key only`() {
        val pair = TestKeys.random()
        val token = EdDsaJws.sign(mapOf("alg" to "EdDSA", "kid" to "k1"), mapOf("sub" to "x", "n" to 1), pair.private)
        val parsed = assertNotNull(EdDsaJws.parse(token, 4096))
        assertEquals("k1", parsed.header.get("kid").stringValue())
        assertEquals(1, parsed.claims.get("n").intValue())
        assertTrue(parsed.verifiedBy(pair.public))
        assertFalse(parsed.verifiedBy(TestKeys.random().public))
        val raw = EdDsaJws.rawPublicKey(pair.public)
        assertTrue(parsed.verifiedBy(assertNotNull(EdDsaJws.publicKey(raw))))

        // The JDK verifies the same bytes: the signature is over the first two parts.
        val (header, claims, signature) = token.split('.')
        assertTrue(
            Signature.getInstance("Ed25519").run {
                initVerify(pair.public)
                update("$header.$claims".toByteArray())
                verify(EdDsaJws.decode(signature))
            },
        )
        val tampered = header + "." + EdDsaJws.encode("""{"sub":"y","n":1}""".toByteArray()) + "." + signature
        assertFalse(assertNotNull(EdDsaJws.parse(tampered, 4096)).verifiedBy(pair.public))
    }

    @Test
    fun `parse refuses anything but three base64url parts of JSON objects and a 64-byte signature`() {
        val pair = TestKeys.random()
        val token = EdDsaJws.sign(mapOf("alg" to "EdDSA"), mapOf("sub" to "x"), pair.private)
        val (header, claims, signature) = token.split('.')
        assertNull(EdDsaJws.parse(token, token.length - 1))
        for (bad in listOf(
            "",
            "a.b",
            "$header.$claims",
            "$header.$claims.$signature.x",
            "$header=.$claims.$signature",
            "$header.${EdDsaJws.encode("[1]".toByteArray())}.$signature",
            "$header.${EdDsaJws.encode("not json".toByteArray())}.$signature",
            "$header.$claims.${EdDsaJws.encode(ByteArray(63))}",
        )) {
            assertNull(EdDsaJws.parse(bad, 4096), bad)
        }
        assertNull(EdDsaJws.publicKey(ByteArray(31)))
        assertNull(EdDsaJws.decode("AA=="))
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
