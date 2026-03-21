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
    @Query(value = "INSERT IGNORE INTO users (id, isu, created_at, auto_sign_limit) VALUES (:id, :isu, NOW(), 3)", nativeQuery = true)
    fun insertIgnore(id: UUID, isu: Int): Int
}