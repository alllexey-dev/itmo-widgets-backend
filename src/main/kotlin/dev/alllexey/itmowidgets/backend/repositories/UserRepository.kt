package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {

    fun findByIsu(isu: Int): User?

    @Modifying
    @Transactional
    @Query(value = "INSERT IGNORE INTO users (id, isu, created_at, settings_id) VALUES (:id, :isu, NOW(), :settingsId)", nativeQuery = true)
    fun insertIgnore(id: UUID, isu: Int, settingsId: UUID): Int

    @Modifying
    @Transactional
    @Query(
        value = """
        INSERT IGNORE INTO user_settings (id, auto_sign_limit)
        VALUES (:id, 3)
    """,
        nativeQuery = true
    )
    fun insertSettingsIgnore(id: UUID): Int
}