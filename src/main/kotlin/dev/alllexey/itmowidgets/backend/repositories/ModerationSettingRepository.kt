package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ModerationSettingRepository : JpaRepository<ModerationSettingEntity, String> {
    fun findAllByKeyStartingWith(prefix: String): List<ModerationSettingEntity>

    @Modifying
    @Query(value = """
        INSERT INTO moderation_settings (key, value, updated_at, updated_by)
        VALUES (:key, :value, :updatedAt, :updatedBy)
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,
            updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
    """, nativeQuery = true)
    fun upsert(key: String, value: String, updatedAt: Instant, updatedBy: UUID)
}
