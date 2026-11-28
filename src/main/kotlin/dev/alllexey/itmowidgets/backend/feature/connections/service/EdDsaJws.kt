package dev.alllexey.itmowidgets.backend.feature.connections.service

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECPrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Compact JWS signed with EdDSA over Ed25519, from the JDK alone: client assertions of confidential clients and the
 * identity tokens Backend signs (docs/contracts/connections.md#confidential-clients). Keys travel as the raw 32-byte
 * public key (base64url, as in a JWK's `x`) and as PKCS#8 for Backend's own private key.
 */
object EdDsaJws {
    const val ALG = "EdDSA"
    const val KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    private const val ED25519 = "Ed25519"

    /** The DER prefix of an X.509 `SubjectPublicKeyInfo` holding a raw Ed25519 key. */
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val json: JsonMapper = JsonMapper.builder().build()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val BASE64URL = Regex("^[A-Za-z0-9_-]*$")

    /** A token split into its parts; [header] and [claims] are JSON objects, nothing is verified yet. */
    class Parsed(val header: JsonNode, val claims: JsonNode, private val signingInput: ByteArray, private val signature: ByteArray) {
        fun verifiedBy(key: PublicKey): Boolean = try {
            Signature.getInstance(ED25519).run {
                initVerify(key)
                update(signingInput)
                verify(signature)
            }
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Base64url without padding, or null when [value] is not that. */
    fun decode(value: String): ByteArray? =
        if (BASE64URL.matches(value)) runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() else null

    /** The Ed25519 public key of its raw 32 bytes; null when they are not a usable key. */
    fun publicKey(raw: ByteArray): PublicKey? = if (raw.size != KEY_BYTES) {
        null
    } else {
        try {
            KeyFactory.getInstance(ED25519).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** The raw 32 bytes of an Ed25519 public key, as a JWK's `x` carries them. */
    fun rawPublicKey(key: PublicKey): ByteArray {
        val encoded = key.encoded
        check(encoded.size == X509_PREFIX.size + KEY_BYTES && encoded.copyOfRange(0, X509_PREFIX.size).contentEquals(X509_PREFIX)) {
            "Not an Ed25519 public key"
        }
        return encoded.copyOfRange(X509_PREFIX.size, encoded.size)
    }

    /** An Ed25519 private key from its PKCS#8 encoding; throws when it is something else. */
    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance(ED25519).generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /**
     * The public key of [privateKey]. The JDK has no call for it, but its key generation derives the key pair from the
     * 32 bytes it draws: drawing the private key's own seed gives that key pair back.
     */
    fun publicKeyOf(privateKey: PrivateKey): PublicKey {
        val seed = (privateKey as EdECPrivateKey).bytes.orElseThrow { IllegalStateException("An Ed25519 private key without its seed") }
        val generator = KeyPairGenerator.getInstance(ED25519)
        generator.initialize(NamedParameterSpec.ED25519, SeedRandom(seed))
        return generator.generateKeyPair().public
    }

    /** Signs [claims] under [header] (both written in their order) with [key]. */
    fun sign(header: Map<String, Any>, claims: Map<String, Any>, key: PrivateKey): String {
        val input = encode(json.writeValueAsBytes(header)) + "." + encode(json.writeValueAsBytes(claims))
        val signature = Signature.getInstance(ED25519).run {
            initSign(key)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return input + "." + encode(signature)
    }

    /** Splits a compact JWS of at most [maxLength] characters; null when it is not one with JSON-object parts. */
    fun parse(token: String, maxLength: Int): Parsed? {
        if (token.length > maxLength) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        val header = parts[0].let(::decode)?.let(::objectOf) ?: return null
        val claims = parts[1].let(::decode)?.let(::objectOf) ?: return null
        val signature = decode(parts[2])?.takeIf { it.size == SIGNATURE_BYTES } ?: return null
        return Parsed(header, claims, (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII), signature)
    }

    private fun objectOf(bytes: ByteArray): JsonNode? = runCatching { json.readTree(bytes) }.getOrNull()?.takeIf { it.isObject }

    /** Hands out one fixed seed, so key generation rebuilds the key pair of that seed. */
    private class SeedRandom(private val seed: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            check(bytes.size == seed.size) { "Unexpected draw of ${bytes.size} bytes" }
            seed.copyInto(bytes)
        }
    }
}
