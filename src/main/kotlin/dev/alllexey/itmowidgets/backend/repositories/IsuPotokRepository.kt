package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.IsuPotokEntity
import dev.alllexey.itmowidgets.backend.model.IsuPotokMemberEntity
import dev.alllexey.itmowidgets.backend.model.IsuPotokMemberId
import dev.alllexey.itmowidgets.backend.model.IsuPotokTeacherEntity
import dev.alllexey.itmowidgets.backend.model.IsuPotokTeacherId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface IsuPotokRepository : JpaRepository<IsuPotokEntity, Long>

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
}
