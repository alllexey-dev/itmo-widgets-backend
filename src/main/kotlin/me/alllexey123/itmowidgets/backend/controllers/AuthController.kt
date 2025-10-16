package me.alllexey123.itmowidgets.backend.controllers

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import me.alllexey123.itmowidgets.backend.configs.JwtConfig
import me.alllexey123.itmowidgets.backend.services.ItmoJwtVerifier
import me.alllexey123.itmowidgets.backend.services.JwtProvider
import me.alllexey123.itmowidgets.backend.services.RefreshTokenService
import me.alllexey123.itmowidgets.backend.services.UserService
import me.alllexey123.itmowidgets.backend.utils.ApiResponse
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ItmoTokenLoginRequest(@field:NotBlank val itmoToken: String, @field:NotBlank val deviceName: String)
data class LoginResponse(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresIn: Long
)

data class RefreshTokenRequest(@field:NotBlank val refreshToken: String)
data class LogoutRequest(@field:NotBlank val refreshToken: String)

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
    fun authenticateViaItmoToken(@Valid @RequestBody request: ItmoTokenLoginRequest): ApiResponse<LoginResponse> {
        val verifiedItmoJwt = itmoJwtVerifier.verifyAndDecode(request.itmoToken)
        val isu = itmoJwtVerifier.getIsu(verifiedItmoJwt)
            ?: throw RuntimeException("ISU ID not found in token.")

        val user = userService.findOrCreateByIsu(isu)
        val accessToken = jwtProvider.generateAccessToken(user.id)
        val refreshToken = refreshTokenService.createRefreshToken(user.id, request.deviceName)

        val responseData = LoginResponse(
            accessToken,
            jwtConfig.accessExpirationMs,
            refreshToken.token,
            jwtConfig.refreshExpirationMs
        )
        return ApiResponse.success(responseData)
    }

    @PostMapping("/refresh")
    @Transactional
    fun refreshToken(@Valid @RequestBody request: RefreshTokenRequest): ApiResponse<LoginResponse> {
        val newRefreshToken = refreshTokenService.rotateRefreshToken(request.refreshToken)
        val newAccessToken = jwtProvider.generateAccessToken(newRefreshToken.user.id)

        val responseData = LoginResponse(
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