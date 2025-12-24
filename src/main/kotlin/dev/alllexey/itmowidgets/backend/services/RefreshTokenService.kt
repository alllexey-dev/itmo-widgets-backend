package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.configs.JwtConfig
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.model.RefreshToken
import dev.alllexey.itmowidgets.backend.repositories.RefreshTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userService: UserService,
    private val jwtConfig: JwtConfig,
) {

    @Transactional
    fun findByToken(token: String): RefreshToken? {
        return refreshTokenRepository.findByToken(token)
    }

    @Transactional
    fun createRefreshToken(userId: UUID): RefreshToken {
        val user = userService.findUserById(userId)

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
            ?: throw BusinessRuleException("Refresh token not found.")

        verifyExpiration(oldToken)

        val user = oldToken.user
//        refreshTokenRepository.delete(oldToken) // do not delete previous token

        return createRefreshToken(user.id)
    }

    @Transactional
    fun verifyExpiration(token: RefreshToken) {
        if (token.expiryDate.isBefore(Instant.now())) {
            refreshTokenRepository.delete(token)
            throw BusinessRuleException("Refresh token was expired.")
        }
        token.lastUsed = Instant.now()
    }

    @Transactional
    fun deleteAllForUser(userId: UUID) {
        val user = userService.findUserById(userId)
        refreshTokenRepository.deleteByUser(user)
    }

    @Transactional
    fun deleteByToken(token: String) {
        refreshTokenRepository.findByToken(token)?.let { refreshTokenRepository.delete(it) }
    }

    @Transactional
    fun findAllByUser(userId: UUID): List<RefreshToken> {
        val user = userService.findUserById(userId)
        return refreshTokenRepository.findByUser(user)
    }
}