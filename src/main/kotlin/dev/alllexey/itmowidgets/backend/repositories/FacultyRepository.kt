package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.FacultyEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface FacultyRepository : JpaRepository<FacultyEntity, Long> {

    @Modifying
    @Query(
        value = """
            INSERT INTO faculties (id, name, short_name)
            VALUES (:id, :name, :shortName)
            ON DUPLICATE KEY UPDATE
                name = :name,
                short_name = :shortName
        """,
        nativeQuery = true
    )
    fun upsert(id: Long, name: String, shortName: String)

}