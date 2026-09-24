package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AdminAuditEntry
import dev.alllexey.itmowidgets.backend.dto.AdminPage
import dev.alllexey.itmowidgets.backend.services.AdminAuditService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Admin-only; the role is checked in the service. Newest entries first. */
@RestController
@RequestMapping("/api/admin/audit")
class AdminAuditController(private val audit: AdminAuditService) {
    @GetMapping
    fun audit(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${AdminPage.DEFAULT_SIZE}") size: Int,
        authentication: Authentication,
    ): ApiResponse<AdminPage<AdminAuditEntry>> = ApiResponse.success(audit.page(authentication.uuid(), page, size))
}
