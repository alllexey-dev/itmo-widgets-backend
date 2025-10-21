package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.RefreshTokenService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.SessionResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/api/session")
class SessionController(private val refreshTokenService: RefreshTokenService) {

    @GetMapping("/all")
    fun getActiveSessions(authentication: Authentication): ApiResponse<List<SessionResponse>> {
        val userId = UUID.fromString(authentication.name)
        return refreshTokenService.findAllByUser(userId).map {
            SessionResponse(tokenId = it.id, lastUsed = it.lastUsed)
        }.toList().let { ApiResponse.success(it) }
    }

    @DeleteMapping("/all")
    fun revokeAllSessions(authentication: Authentication): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        refreshTokenService.deleteAllForUser(userId)
        return ApiResponse.success("All active sessions have been logged out.")
    }
}