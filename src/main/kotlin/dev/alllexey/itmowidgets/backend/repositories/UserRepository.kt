package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
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

    /**
     * Admin search, newest first. [pattern] is a lowercase `%…%` LIKE pattern escaped with `!` and matches the
     * name or a stored group name; [isuPrefix] matches the ISU as text. A null pattern lists everybody.
     */
    @Query(
        value = """
        SELECT new dev.alllexey.itmowidgets.backend.repositories.UserSummaryRow(u.id, u.isu, u.name, u.pictureUrl, u.createdAt)
        FROM User u
        WHERE :pattern IS NULL
           OR CAST(u.isu AS String) LIKE :isuPrefix
           OR LOWER(u.name) LIKE :pattern ESCAPE '!'
           OR EXISTS (SELECT g.id FROM User x JOIN x.groups g WHERE x.id = u.id AND LOWER(g.name) LIKE :pattern ESCAPE '!')
        ORDER BY u.createdAt DESC, u.id
        """,
        countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE :pattern IS NULL
           OR CAST(u.isu AS String) LIKE :isuPrefix
           OR LOWER(u.name) LIKE :pattern ESCAPE '!'
           OR EXISTS (SELECT g.id FROM User x JOIN x.groups g WHERE x.id = u.id AND LOWER(g.name) LIKE :pattern ESCAPE '!')
        """,
    )
    fun search(pattern: String?, isuPrefix: String, pageable: Pageable): Page<UserSummaryRow>

    @Query("SELECT new dev.alllexey.itmowidgets.backend.repositories.UserSummaryRow(u.id, u.isu, u.name, u.pictureUrl, u.createdAt) FROM User u WHERE u.id IN :ids")
    fun findSummaryRows(ids: Collection<UUID>): List<UserSummaryRow>

    @Query("""
        SELECT new dev.alllexey.itmowidgets.backend.repositories.UserGroupRow(u.id, g.name, g.course, f.shortName)
        FROM User u JOIN u.groups g JOIN g.faculty f
        WHERE u.id IN :ids
        """)
    fun findGroupRows(ids: Collection<UUID>): List<UserGroupRow>

    fun countByCreatedAtGreaterThanEqual(since: Instant): Long

    /** Registrations per calendar day in [zone] since [from]. */
    @Query(
        nativeQuery = true,
        value = """
        SELECT to_char(CAST(created_at AT TIME ZONE :zone AS date), 'YYYY-MM-DD') AS label, COUNT(*) AS total
        FROM users WHERE created_at >= :from GROUP BY 1
        """,
    )
    fun countCreatedPerDay(from: Instant, zone: String): List<LabelCount>
}
