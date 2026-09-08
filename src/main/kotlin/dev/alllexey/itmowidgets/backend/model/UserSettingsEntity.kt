package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.UserSettings
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "user_settings")
class UserSettingsEntity(
    @Id
    var id: UUID,
    var autoSignLimit: Int = 3,
    var sportSharing: Boolean = false,
    var scheduleSharing: Boolean = false,
    // Nullable additive columns preserve old users' choices without a privacy-widening backfill.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sport_visibility", length = 16, columnDefinition = "varchar(16)")
    var sportVisibility: SharingVisibility? = null,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "schedule_visibility", length = 16, columnDefinition = "varchar(16)")
    var scheduleVisibility: SharingVisibility? = null,
) {
    fun effectiveSportVisibility(): SharingVisibility = sportVisibility ?: legacyVisibility(sportSharing)

    fun effectiveScheduleVisibility(): SharingVisibility = scheduleVisibility ?: legacyVisibility(scheduleSharing)

    companion object {
        private fun legacyVisibility(enabled: Boolean) =
            if (enabled) SharingVisibility.FRIENDS else SharingVisibility.NOBODY

        /** Legacy own-user projection; other users must go through UserPrivacyService. */
        fun UserSettingsEntity.toDto(): UserSettings = UserSettings(
            sportSharing = effectiveSportVisibility() != SharingVisibility.NOBODY,
            scheduleSharing = effectiveScheduleVisibility() != SharingVisibility.NOBODY
        )
    }
}
