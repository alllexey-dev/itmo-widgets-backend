package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {

    fun findByIsu(isu: Int): User?

    /** Lock only the owner row: eager optional settings must not enter FOR UPDATE joins. */
    @Query(value = "SELECT id FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(id: UUID): UUID?

    @Query(value = "SELECT id FROM users WHERE isu = :isu FOR UPDATE", nativeQuery = true)
    fun lockByIsu(isu: Int): UUID?

    @Modifying
    @Transactional
    @Query(
        value = """
        INSERT INTO users (id, isu, created_at)
        VALUES (:id, :isu, CURRENT_TIMESTAMP)
        ON CONFLICT (isu) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIgnore(id: UUID, isu: Int): Int

    @Modifying
    @Transactional
    @Query(
        value = """
        INSERT INTO user_settings (user_id) VALUES (:id)
        ON CONFLICT (user_id) DO NOTHING
    """,
        nativeQuery = true
    )
    fun insertSettingsIgnore(id: UUID): Int

    @Query("SELECT u.id FROM User u WHERE u.isu = :isu")
    fun findIdByIsu(isu: Int): UUID?

    fun findAllByIsuIn(isu: Collection<Int>): List<User>
}
