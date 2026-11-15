package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditAction
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditFilter
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditPageService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Admin-only; the role is checked in the service. Newest entries first; every filter is optional. */
@RestController
@RequestMapping("/api/admin/audit")
class AdminAuditController(private val audit: AdminAuditPageService) {
    @GetMapping
    fun audit(
        @RequestParam(required = false) actorIsu: Int?,
        @RequestParam(required = false) action: AdminAuditAction?,
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${AdminPage.DEFAULT_SIZE}") size: Int,
        authentication: Authentication,
    ): ApiResponse<AdminPage<AdminAuditEntry>> = ApiResponse.success(
        audit.page(authentication.uuid(), AdminAuditFilter(actorIsu, action, targetType, target, from, to), page, size),
    )
}
