package me.alllexey123.itmowidgets.backend.model

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
class DeviceToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var token: String,

    var userId: String

) {
}