package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.QualificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface QualificationRepository : JpaRepository<QualificationEntity, Long> {

    @Modifying
    @Query(
        value = """
            INSERT INTO qualifications (code, name)
            VALUES (:code, :name)
            ON DUPLICATE KEY UPDATE
                name = :name
        """,
        nativeQuery = true
    )
    fun upsert(code: Long, name: String)

}