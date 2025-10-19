package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.RefreshToken
import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    fun findByToken(token: String): RefreshToken?
    fun findByUser(user: User): List<RefreshToken>

    @Modifying
    fun deleteByUser(user: User)
}