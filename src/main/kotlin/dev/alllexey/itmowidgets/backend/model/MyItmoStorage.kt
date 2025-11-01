package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class MyItmoStorage(
    @Id
    val id: Long,

    @Column(columnDefinition = "TEXT")
    var refreshToken: String?,

    var refreshTokenExpiresAt: Long,

    @Column(columnDefinition = "TEXT")
    var accessToken: String?,

    var accessTokenExpiresAt: Long,

    @Column(columnDefinition = "TEXT")
    var idToken: String?,
)