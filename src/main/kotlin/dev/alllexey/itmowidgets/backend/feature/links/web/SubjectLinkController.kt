package dev.alllexey.itmowidgets.backend.feature.links.web

import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Authors get their current study groups after the service transaction, like every profile response. */
@RestController
@RequestMapping("/api")
class SubjectLinkController(
    private val links: SubjectLinkService,
    private val currentGroups: CurrentStudyGroupsService,
) {
    @GetMapping("/subjects/{subjectId}/links")
    fun links(@PathVariable subjectId: Long, @RequestParam period: String, authentication: Authentication): ApiResponse<SubjectLinksResponse> =
        ApiResponse.success(decorate(links.links(authentication.uuid(), subjectId, period)))

    @PutMapping("/links/{id}")
    fun save(@PathVariable id: UUID, @RequestBody request: SaveSubjectLinkRequest, authentication: Authentication): ApiResponse<SubjectLink> =
        ApiResponse.success(decorate(links.save(authentication.uuid(), id, request)))

    @DeleteMapping("/links/{id}")
    fun delete(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        links.delete(authentication.uuid(), id)
        return ApiResponse.success(Unit)
    }

    @PutMapping("/subjects/{subjectId}/links/pin")
    fun pin(@PathVariable subjectId: Long, @RequestBody request: PinSubjectLinkRequest, authentication: Authentication): ApiResponse<SubjectLinksResponse> =
        ApiResponse.success(decorate(links.pin(authentication.uuid(), subjectId, request)))

    @PutMapping("/links/{id}/vote")
    fun vote(@PathVariable id: UUID, @RequestBody request: ResourceVoteRequest, authentication: Authentication): ApiResponse<SubjectLink> =
        ApiResponse.success(decorate(links.vote(authentication.uuid(), id, request.value)))

    @PostMapping("/links/{id}/report")
    fun report(@PathVariable id: UUID, @RequestBody request: ModerationReportRequest, authentication: Authentication): ApiResponse<SubjectLink> =
        ApiResponse.success(decorate(links.report(authentication.uuid(), id, request)))

    private fun decorate(link: SubjectLink): SubjectLink = link.copy(author = link.author?.let(currentGroups::userData))

    private fun decorate(response: SubjectLinksResponse): SubjectLinksResponse = response.copy(
        mine = response.mine.map(::decorate), shared = response.shared.map(::decorate), previous = response.previous.map(::decorate))
}
