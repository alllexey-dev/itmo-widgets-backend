package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.other.TokenResponse
import dev.alllexey.itmowidgets.backend.model.MyItmoStorage
import dev.alllexey.itmowidgets.backend.model.MyItmoTokenSnapshot
import dev.alllexey.itmowidgets.backend.repositories.MyItmoRepository
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Credential rotation is committed independently of the operation which requested it.
 * Only this bean owns token transactions; no entity or lock survives a store call.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class MyItmoTokenStore(
    private val repository: MyItmoRepository,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun readSnapshot(): MyItmoTokenSnapshot {
        val stored = repository.findById(1L).orElse(null)
        return MyItmoTokenSnapshot(
            accessToken = stored?.accessToken,
            accessExpiresAt = stored?.accessTokenExpiresAt ?: 0L,
            refreshToken = stored?.refreshToken,
            refreshExpiresAt = stored?.refreshTokenExpiresAt ?: 0L,
            idToken = stored?.idToken,
        )
    }

    fun update(response: TokenResponse) {
        val stored = lockedStorage()
        val now = clock.millis()
        stored.accessToken = response.accessToken
        stored.accessTokenExpiresAt = now + response.expiresIn * 1000L
        stored.refreshToken = response.refreshToken
        stored.refreshTokenExpiresAt = now + response.refreshExpiresIn * 1000L
        stored.idToken = response.idToken
    }

    fun initializeFromBootstrap(refreshToken: String?) {
        val stored = lockedStorage()
        if (!refreshToken.isNullOrBlank() && stored.refreshToken.isNullOrBlank()) {
            stored.refreshToken = refreshToken
            stored.refreshTokenExpiresAt = clock.millis() + Duration.ofDays(30).toMillis()
        }
    }

    fun setAccessToken(accessToken: String?) {
        lockedStorage().accessToken = accessToken
    }

    fun setAccessExpiresAt(accessExpiresAt: Long) {
        lockedStorage().accessTokenExpiresAt = accessExpiresAt
    }

    fun setRefreshToken(refreshToken: String?) {
        lockedStorage().refreshToken = refreshToken
    }

    fun setRefreshExpiresAt(refreshExpiresAt: Long) {
        lockedStorage().refreshTokenExpiresAt = refreshExpiresAt
    }

    fun setIdToken(idToken: String?) {
        lockedStorage().idToken = idToken
    }

    private fun lockedStorage(): MyItmoStorage {
        // The insert also serializes concurrent creation before the first row exists.
        repository.insertSingletonIfAbsent()
        return checkNotNull(repository.getWithLock()) { "Technical credential storage is unavailable" }
    }
}
