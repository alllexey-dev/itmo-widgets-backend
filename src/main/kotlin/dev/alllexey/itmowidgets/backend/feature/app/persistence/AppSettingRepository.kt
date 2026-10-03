package dev.alllexey.itmowidgets.backend.feature.app.persistence

import dev.alllexey.itmowidgets.backend.feature.app.model.AppSettingEntity
import org.springframework.data.jpa.repository.JpaRepository

interface AppSettingRepository : JpaRepository<AppSettingEntity, String>
