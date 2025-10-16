package me.alllexey123.itmowidgets.backend.controllers

import me.alllexey123.itmowidgets.backend.services.RefreshTokenService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class SessionResponse(val tokenId: UUID, val lastUsed: Instant)

@RestController
@RequestMapping("/api/sessions")
class SessionController(private val refreshTokenService: RefreshTokenService) {

    @GetMapping
    fun getActiveSessions(authentication: Authentication): List<SessionResponse> {
        val userId = UUID.fromString(authentication.name)
        return refreshTokenService.findAllByUser(userId).map {
            SessionResponse(tokenId = it.id, lastUsed = it.lastUsed)
        }
    }

    @DeleteMapping("/all")
    fun revokeAllSessions(authentication: Authentication): ResponseEntity<String> {
        val userId = UUID.fromString(authentication.name)
        refreshTokenService.deleteAllForUser(userId)
        return ResponseEntity.ok("All active sessions have been logged out.")
    }
}