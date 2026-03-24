package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.UserSettings
import jakarta.persistence.*
import java.util.*

@Entity
@Table(name = "user_settings")
class UserSettingsEntity(

    @Id
    var id: UUID,

    var autoSignLimit: Int = 3,

    var sportLogging: Boolean = false,

    var scheduleLogging: Boolean = false,
) {

    companion object {
        fun UserSettingsEntity.toDto(): UserSettings {
            return UserSettings(
                sportLogging = sportLogging,
                scheduleLogging = scheduleLogging
            )
        }
    }
}