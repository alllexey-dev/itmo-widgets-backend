package dev.alllexey.itmowidgets.backend.testing

import dev.alllexey.itmowidgets.backend.feature.connections.service.EdDsaJws
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Base64

/**
 * Ed25519 keys for tests, never used anywhere else: [random] ones, and [fixed] ones made from a counter seed so a
 * recorded contract fixture stays the same between runs.
 */
object TestKeys {
    /** The DER prefix of a PKCS#8 v1 `PrivateKeyInfo` holding a 32-byte Ed25519 seed. */
    private val PKCS8_PREFIX = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)

    fun random(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** The key pair of the seed `n, n+1, ..., n+31`. */
    fun fixed(n: Int): KeyPair {
        val private = EdDsaJws.privateKey(PKCS8_PREFIX + ByteArray(EdDsaJws.KEY_BYTES) { (n + it).toByte() })
        return KeyPair(EdDsaJws.publicKeyOf(private), private)
    }

    /** The registry form of a public key: base64url of its raw 32 bytes. */
    fun publicKeyText(pair: KeyPair): String = EdDsaJws.encode(EdDsaJws.rawPublicKey(pair.public))

    /** The configuration form of a private key: base64 of its PKCS#8 encoding. */
    fun privateKeyText(key: PrivateKey): String = Base64.getEncoder().encodeToString(key.encoded)
}
