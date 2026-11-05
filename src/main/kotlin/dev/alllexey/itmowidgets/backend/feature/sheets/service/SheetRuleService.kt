package dev.alllexey.itmowidgets.backend.feature.sheets.service

import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.model.ResourceUrlPolicy
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleEntity
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatus
import dev.alllexey.itmowidgets.backend.feature.sheets.web.SheetRuleResponse
import dev.alllexey.itmowidgets.backend.feature.sheets.web.SheetRuleState
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.ratelimit.WindowLimiter
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The AI rule of the Google sheet behind a link. Only a link the caller sees whose approved content is shared with a
 * flow or everybody qualifies (ADR 0038); any other link is 404, so its existence does not leak. Private, hidden and
 * pending content never reaches the analyzer: the approved revision decides, and the job checks the sharing again.
 */
@Service
class SheetRuleService(
    private val links: SubjectLinkService,
    private val store: SheetRuleStore,
    private val job: SheetRuleJob,
    private val config: SheetRulesConfig,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    private val limiter = WindowLimiter(config.userRequestsPerHour, Duration.ofHours(1), clock)

    fun rule(viewerId: UUID, linkId: UUID): SheetRuleResponse = response(store.find(sheetId(viewerId, linkId)))

    /** Queues an analysis when there is none or the stored one is due; the per-user limit counts only those. */
    fun request(viewerId: UUID, linkId: UUID): SheetRuleResponse {
        if (!config.enabled) throw BusinessRuleException(DISABLED)
        val sheetId = sheetId(viewerId, linkId)
        val now = clock.instant()
        if (store.find(sheetId)?.isDue(now.minus(config.staleAfter)) != false) {
            if (!limiter.tryAcquire(viewerId)) throw TooManyRequestsException("Too many sheet analyses, try again later")
            if (store.request(sheetId, now)) job.kick()
        }
        return response(store.find(sheetId))
    }

    private fun sheetId(viewerId: UUID, linkId: UUID): String {
        val content = links.sharedContent(viewerId, linkId)?.takeIf { it.visibility in SHARED } ?: throw NotFoundException("Link not found")
        return ResourceUrlPolicy.googleSheetId(content.normalizedUrl) ?: throw BusinessRuleException(NOT_A_SHEET)
    }

    private fun response(row: SheetRuleEntity?): SheetRuleResponse {
        if (row == null) return SheetRuleResponse(SheetRuleState.NONE, null, null, null)
        val state = SheetRuleState.valueOf(row.status.name)
        return SheetRuleResponse(
            state,
            row.rule?.let(jsonMapper::readTree),
            row.analyzedAt,
            row.lastError.takeIf { row.status != SheetRuleStatus.READY },
        )
    }

    companion object {
        const val DISABLED = "sheet_rules_disabled"
        const val NOT_A_SHEET = "not_a_google_sheet"
        private val SHARED = setOf(LinkVisibility.FLOW, LinkVisibility.ALL)
    }
}
