package me.alllexey123.itmowidgets.backend.services

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import me.alllexey123.itmowidgets.backend.configs.JwtConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*
import javax.crypto.SecretKey

@Service
class JwtProvider(
    private val jwtConfig: JwtConfig
) {

    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtConfig.secret))
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(JwtProvider::class.java)
    }

    fun generateAccessToken(userId: UUID): String {
        val now = Date()
        val expiryDate = Date(now.time + jwtConfig.accessExpirationMs)

        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey)
            .compact()
    }

    fun getUserIdFromToken(token: String): String {
        val claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload

        return claims.subject
    }

    fun validateToken(token: String): Boolean {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)
            return true
        } catch (e: Exception) {
            log.warn("Invalid JWT token passed: ${e.message}")
        }
        return false
    }


}