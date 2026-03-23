package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.util.*

@Entity
@Table(name = "user_settings")
class UserSettings(

    @Id
    var id: UUID,

    var autoSignLimit: Int = 3,
)