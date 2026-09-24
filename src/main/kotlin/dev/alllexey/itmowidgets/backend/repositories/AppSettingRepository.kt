package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.AppSettingEntity
import org.springframework.data.jpa.repository.JpaRepository

interface AppSettingRepository : JpaRepository<AppSettingEntity, String>
