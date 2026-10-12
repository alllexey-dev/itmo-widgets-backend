package dev.alllexey.itmowidgets.backend.feature.links.web

import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api")
class SubjectLinkController(private val links: SubjectLinkService) {
    @GetMapping("/subjects/{subjectId}/links")
    fun links(
        @PathVariable subjectId: Long,
        @RequestParam period: String,
        authentication: Authentication,
    ): ApiResponse<SubjectLinksResponse> = ApiResponse.success(links.links(authentication.uuid(), subjectId, period))

    @PutMapping("/links/{id}")
    fun save(
        @PathVariable id: UUID,
        @RequestBody request: SaveSubjectLinkRequest,
        authentication: Authentication,
    ): ApiResponse<SubjectLink> = ApiResponse.success(links.save(authentication.uuid(), id, request))

    @DeleteMapping("/links/{id}")
    fun delete(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        links.delete(authentication.uuid(), id)
        return ApiResponse.success(Unit)
    }

    @PutMapping("/subjects/{subjectId}/links/pin")
    fun pin(
        @PathVariable subjectId: Long,
        @RequestBody request: PinSubjectLinkRequest,
        authentication: Authentication,
    ): ApiResponse<SubjectLinksResponse> = ApiResponse.success(links.pin(authentication.uuid(), subjectId, request))

    @PutMapping("/links/{id}/vote")
    fun vote(@PathVariable id: UUID, @RequestBody request: ResourceVoteRequest, authentication: Authentication): ApiResponse<SubjectLink> =
        ApiResponse.success(links.vote(authentication.uuid(), id, request.value))

    @PostMapping("/links/{id}/report")
    fun report(
        @PathVariable id: UUID,
        @RequestBody request: ModerationReportRequest,
        authentication: Authentication,
    ): ApiResponse<SubjectLink> = ApiResponse.success(links.report(authentication.uuid(), id, request))
}
