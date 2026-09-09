package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.GroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface GroupRepository : JpaRepository<GroupEntity, UUID> {

    @Modifying
    @Query(
        value = """
            INSERT INTO groups (id, name, course, faculty_id, qualification_id)
            VALUES (:id, :name, :course, :facultyId, :qualificationCode)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                course = EXCLUDED.course,
                faculty_id = EXCLUDED.faculty_id,
                qualification_id = EXCLUDED.qualification_id
        """,
        nativeQuery = true
    )
    fun upsert(
        id: UUID,
        name: String,
        course: Int,
        facultyId: Long,
        qualificationCode: Long
    )

    fun findAllByNameIn(names: Collection<String>): List<GroupEntity>

    @Query("""
        SELECT DISTINCT
        g FROM GroupEntity g
        JOIN g.users u
        WHERE u.id IN :userIds
        """)
    fun findAllByUserIds(userIds: Collection<UUID>): List<GroupEntity>
}
