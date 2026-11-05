package dev.alllexey.itmowidgets.backend.feature.sheets.web

import dev.alllexey.itmowidgets.backend.feature.sheets.service.SheetRuleService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class SheetRuleController(private val rules: SheetRuleService) {
    @GetMapping("/links/{id}/sheet-rule")
    fun rule(@PathVariable id: UUID, authentication: Authentication): ApiResponse<SheetRuleResponse> =
        ApiResponse.success(rules.rule(authentication.uuid(), id))

    /** 202 while the analysis waits or runs, 200 once it has an answer. */
    @PostMapping("/links/{id}/sheet-rule")
    fun request(@PathVariable id: UUID, authentication: Authentication): ResponseEntity<ApiResponse<SheetRuleResponse>> {
        val response = rules.request(authentication.uuid(), id)
        val status = if (response.status == SheetRuleState.PENDING || response.status == SheetRuleState.RUNNING) {
            HttpStatus.ACCEPTED
        } else {
            HttpStatus.OK
        }
        return ResponseEntity.status(status).body(ApiResponse.success(response))
    }
}
