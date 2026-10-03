package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUsersService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** Admin-only; the role is checked in the service. Only MODERATOR is granted or revoked here. */
@RestController
@RequestMapping("/api/admin/users")
class AdminUsersController(private val users: AdminUsersService) {
    @GetMapping
    fun search(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${AdminPage.DEFAULT_SIZE}") size: Int,
        authentication: Authentication,
    ): ApiResponse<AdminPage<AdminUserItem>> = ApiResponse.success(users.search(authentication.uuid(), query, page, size))

    @GetMapping("/{isu}")
    fun detail(@PathVariable isu: Int, authentication: Authentication): ApiResponse<AdminUserDetail> =
        ApiResponse.success(users.detail(authentication.uuid(), isu))

    @PutMapping("/{isu}/roles/{role}")
    fun grant(@PathVariable isu: Int, @PathVariable role: String, authentication: Authentication): ApiResponse<List<String>> =
        ApiResponse.success(users.grant(authentication.uuid(), isu, role))

    @DeleteMapping("/{isu}/roles/{role}")
    fun revoke(@PathVariable isu: Int, @PathVariable role: String, authentication: Authentication): ApiResponse<List<String>> =
        ApiResponse.success(users.revoke(authentication.uuid(), isu, role))
}
