package me.alllexey123.itmowidgets.backend.services

import me.alllexey123.itmowidgets.backend.configs.JwtConfig
import me.alllexey123.itmowidgets.backend.model.RefreshToken
import me.alllexey123.itmowidgets.backend.repositories.RefreshTokenRepository
import me.alllexey123.itmowidgets.backend.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userRepository: UserRepository,
    private val jwtConfig: JwtConfig,
) {

    @Transactional
    fun findByToken(token: String): RefreshToken? {
        return refreshTokenRepository.findByToken(token)
    }

    @Transactional
    fun createRefreshToken(userId: UUID): RefreshToken {
        val user = userRepository.findById(userId).orElseThrow { RuntimeException("User not found with ID: $userId") }

        val token = RefreshToken(
            user = user,
            token = UUID.randomUUID().toString(),
            expiryDate = Instant.now().plusMillis(jwtConfig.refreshExpirationMs)
        )

        return refreshTokenRepository.save(token)
    }

    @Transactional
    fun rotateRefreshToken(oldTokenValue: String): RefreshToken {
        val oldToken = findByToken(oldTokenValue)
            ?: throw RuntimeException("Refresh token not found.")

        verifyExpiration(oldToken)

        val user = oldToken.user
        refreshTokenRepository.delete(oldToken)

        return createRefreshToken(user.id)
    }

    @Transactional
    fun verifyExpiration(token: RefreshToken) {
        if (token.expiryDate.isBefore(Instant.now())) {
            refreshTokenRepository.delete(token)
            throw RuntimeException("Refresh token was expired.")
        }
        token.lastUsed = Instant.now()
    }

    @Transactional
    fun deleteAllForUser(userId: UUID) {
        val user = userRepository.findById(userId).orElseThrow { RuntimeException("User not found with ID: $userId") }
        refreshTokenRepository.deleteByUser(user)
    }

    @Transactional
    fun deleteByToken(token: String) {
        refreshTokenRepository.findByToken(token)?.let { refreshTokenRepository.delete(it) }
    }

    @Transactional
    fun findAllByUser(userId: UUID): List<RefreshToken> {
        val user = userRepository.findById(userId).orElseThrow { RuntimeException("User not found with ID: $userId") }
        return refreshTokenRepository.findByUser(user)
    }
}