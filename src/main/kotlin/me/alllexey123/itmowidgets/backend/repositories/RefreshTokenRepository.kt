package me.alllexey123.itmowidgets.backend.repositories

import me.alllexey123.itmowidgets.backend.model.RefreshToken
import me.alllexey123.itmowidgets.backend.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    fun findByToken(token: String): RefreshToken?
    fun findByUser(user: User): List<RefreshToken>

    @Modifying
    fun deleteByUser(user: User)
}