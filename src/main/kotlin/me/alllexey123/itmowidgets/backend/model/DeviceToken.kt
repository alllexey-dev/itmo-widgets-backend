package me.alllexey123.itmowidgets.backend.model

import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class DeviceToken(
    @Id
    var token: String,

    var userId: String

) {
}