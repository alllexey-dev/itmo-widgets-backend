package dev.alllexey.itmowidgets.backend.feature.connections.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/** Random secrets for connection tokens and pairing claims, stored only as SHA-256. */
internal object ConnectionTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val secretPattern = Regex("^[A-Za-z0-9_-]{43}$")

    /** 32 random bytes, base64url. */
    fun random(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)

    fun isWellFormed(secret: String): Boolean = secretPattern.matches(secret)

    fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** Constant-time comparison of a presented secret against a stored hash. */
    fun matches(secret: String, hash: String): Boolean =
        MessageDigest.isEqual(sha256(secret).toByteArray(Charsets.US_ASCII), hash.toByteArray(Charsets.US_ASCII))
}
