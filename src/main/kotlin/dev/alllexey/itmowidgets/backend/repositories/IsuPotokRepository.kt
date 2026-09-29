package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.IsuPotokEntity
import dev.alllexey.itmowidgets.backend.model.IsuPotokMemberEntity
import dev.alllexey.itmowidgets.backend.model.IsuPotokMemberId
import dev.alllexey.itmowidgets.backend.model.IsuPotokTeacherEntity
import dev.alllexey.itmowidgets.backend.model.IsuPotokTeacherId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface IsuPotokRepository : JpaRepository<IsuPotokEntity, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE IsuPotokEntity p SET p.membersCheckedAt = NULL WHERE p.membersCheckedAt < :cutoff")
    fun forgetMembersCheckedBefore(cutoff: Instant): Int

    /** Flows with stale or unknown teachers and no cached members; their teacher rows cascade. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        DELETE FROM isu_potoks p
        WHERE (p.teachers_checked_at IS NULL OR p.teachers_checked_at < :cutoff)
          AND NOT EXISTS (SELECT 1 FROM isu_potok_members m WHERE m.potok_id = p.potok_id)
        """, nativeQuery = true)
    fun deleteStale(cutoff: Instant): Int
}

interface IsuPotokTeacherRepository : JpaRepository<IsuPotokTeacherEntity, IsuPotokTeacherId> {
    @Query("SELECT t.id.teacherIsu FROM IsuPotokTeacherEntity t WHERE t.id.potokId = :potokId ORDER BY t.id.teacherIsu")
    fun findTeachers(potokId: Long): List<Int>

    fun existsByIdTeacherIsu(teacherIsu: Int): Boolean

    @Modifying
    @Query("DELETE FROM IsuPotokTeacherEntity t WHERE t.id.potokId = :potokId")
    fun deleteAllByIdPotokId(potokId: Long): Int
}

interface IsuPotokMemberRepository : JpaRepository<IsuPotokMemberEntity, IsuPotokMemberId> {
    @Modifying
    @Query("DELETE FROM IsuPotokMemberEntity m WHERE m.id.potokId = :potokId")
    fun deleteAllByIdPotokId(potokId: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        DELETE FROM isu_potok_members m USING isu_potoks p
        WHERE p.potok_id = m.potok_id AND p.members_checked_at < :cutoff
        """, nativeQuery = true)
    fun deleteCheckedBefore(cutoff: Instant): Int
}
