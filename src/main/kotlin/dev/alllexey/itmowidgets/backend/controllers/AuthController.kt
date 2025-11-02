package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.JwtConfig
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.services.JwtProvider
import dev.alllexey.itmowidgets.backend.services.RefreshTokenService
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.model.*
import jakarta.validation.Valid
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.naming.AuthenticationException

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val itmoJwtVerifier: ItmoJwtVerifier,
    private val userService: UserService,
    private val jwtProvider: JwtProvider,
    private val refreshTokenService: RefreshTokenService,
    private val jwtConfig: JwtConfig
) {

    @PostMapping("/itmo-token")
    @Transactional
    fun authenticateViaItmoToken(@Valid @RequestBody request: ItmoTokenLoginRequest): ApiResponse<TokenResponse> {
        val verifiedItmoJwt = itmoJwtVerifier.verifyAndDecode(request.itmoToken)
        val isu = itmoJwtVerifier.getIsu(verifiedItmoJwt)
            ?: throw AuthenticationException("ISU ID not found in token.")

        val user = userService.findOrCreateByIsu(isu)
        val accessToken = jwtProvider.generateAccessToken(user.id)
        val refreshToken = refreshTokenService.createRefreshToken(user.id)

        val responseData = TokenResponse(
            accessToken,
            jwtConfig.accessExpirationMs,
            refreshToken.token,
            jwtConfig.refreshExpirationMs
        )
        return ApiResponse.success(responseData)
    }

    @PostMapping("/refresh")
    @Transactional
    fun refreshToken(@Valid @RequestBody request: RefreshTokenRequest): ApiResponse<TokenResponse> {
        val newRefreshToken = refreshTokenService.rotateRefreshToken(request.refreshToken)
        val newAccessToken = jwtProvider.generateAccessToken(newRefreshToken.user.id)

        val responseData = TokenResponse(
            newAccessToken,
            jwtConfig.accessExpirationMs,
            newRefreshToken.token,
            jwtConfig.refreshExpirationMs
        )
        return ApiResponse.success(responseData)
    }

    @PostMapping("/logout")
    @Transactional
    fun logoutUser(@Valid @RequestBody request: LogoutRequest): ApiResponse<String> {
        refreshTokenService.deleteByToken(request.refreshToken)
        return ApiResponse.success("Logout successful for the specified device.")
    }
}