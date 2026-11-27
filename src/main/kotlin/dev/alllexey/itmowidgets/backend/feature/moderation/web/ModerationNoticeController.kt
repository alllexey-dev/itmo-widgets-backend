package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationNoticeService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** The caller's own moderation notices for the app's bell (docs/contracts/notifications.md#moderation-notices). */
@RestController
class ModerationNoticeController(private val notices: ModerationNoticeService) {
    @GetMapping("/api/users/me/moderation-notices")
    fun notices(authentication: Authentication): ApiResponse<List<ModerationNotice>> =
        ApiResponse.success(notices.recent(authentication.uuid()))
}
