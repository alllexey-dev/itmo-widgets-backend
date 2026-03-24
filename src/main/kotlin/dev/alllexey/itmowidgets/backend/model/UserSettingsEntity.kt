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

    var sportSharing: Boolean = false,

    var scheduleSharing: Boolean = false,
) {

    companion object {
        fun UserSettingsEntity.toDto(): UserSettings {
            return UserSettings(
                sportSharing = sportSharing,
                scheduleSharing = scheduleSharing
            )
        }
    }
}