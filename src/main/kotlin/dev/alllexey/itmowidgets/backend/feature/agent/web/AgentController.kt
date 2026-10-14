package dev.alllexey.itmowidgets.backend.feature.agent.web

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenEntity
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenOrigin
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentAccessService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentConfig
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentEventsSlice
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentPollService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionsService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.requireSignedInWithin
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** Agent-token routes (`/me`, `/events`, `/self`) and the owner's routes (`/access`, `/tokens`); see docs/contracts/agent.md. */
@RestController
@RequestMapping("/api/agent")
class AgentController(
    private val tokens: AgentTokenService,
    private val access: AgentAccessService,
    private val polls: AgentPollService,
    private val users: UserService,
    private val connections: ConnectionsService,
    private val config: AgentConfig,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    @GetMapping("/me")
    fun me(authentication: Authentication): ApiResponse<AgentIdentity> {
        val agent = authentication.agent()
        val scopes = agent.scopes.mapNotNull(AgentScope::fromWire).sorted()
        return ApiResponse.success(AgentIdentity(users.findUserById(agent.userId).isu, agent.tokenId, agent.tokenName, scopes))
    }

    @GetMapping("/events")
    fun events(
        @RequestParam(defaultValue = "0") after: Long,
        @RequestParam(defaultValue = "0") wait: Int,
        @RequestParam(defaultValue = "none") itmo: String,
        authentication: Authentication,
    ): DeferredResult<ApiResponse<AgentEventsPage>> {
        val result = DeferredResult<ApiResponse<AgentEventsPage>>(TIMEOUT_MILLIS)
        polls.poll(authentication.agent(), after, wait, itmo, {
            result.setResult(ApiResponse.success(page(it)))
        }, { result.setErrorResult(it) })
        return result
    }

    /** The agent's `unpair`: the same revocation as removing the device in the app; a second call is 401 like any other. */
    @DeleteMapping("/self")
    fun revokeSelf(authentication: Authentication): ApiResponse<Unit> {
        val agent = authentication.agent()
        connections.revoke(agent.userId, agent.tokenId)
        return ApiResponse.success(Unit)
    }

    @GetMapping("/access")
    fun access(authentication: Authentication): ApiResponse<AgentAccess> =
        ApiResponse.success(AgentAccess(access.isEnabled(authentication.owner())))

    /** The fallback for an agent set up by hand; pairing is the normal way. */
    @PostMapping("/tokens")
    fun create(@RequestBody request: CreateAgentTokenRequest, authentication: Authentication): ApiResponse<CreatedAgentToken> {
        val userId = authentication.owner()
        val origin = if (authentication is WebSessionAuthentication) {
            authentication.requireSignedInWithin(config.createRecentAuth, clock)
            AgentTokenOrigin.WEB
        } else {
            AgentTokenOrigin.APP
        }
        val issued = tokens.issue(userId, request.name, request.scopes.toSet(), origin)
        return ApiResponse.success(CreatedAgentToken(issued.token, issued.entity.info()))
    }

    private fun page(slice: AgentEventsSlice) = AgentEventsPage(
        slice.events.map { AgentEvent(it.seq, it.dedupKey, it.type, it.createdAt, it.expiresAt, jsonMapper.readTree(it.payload)) },
        slice.nextCursor,
        slice.hasMore,
        slice.gap,
    )

    private fun Authentication.agent(): AgentAuthentication =
        this as? AgentAuthentication ?: throw AccessDeniedException("Agent token required")

    /** The app or the web; never an agent token (the route policy already refuses it). */
    private fun Authentication.owner() = if (this is BearerAuthentication || this is WebSessionAuthentication) {
        uuid()
    } else {
        throw AccessDeniedException("App or web session required")
    }

    companion object {
        /** A backstop only: the poll answers itself after at most 30 seconds. */
        private const val TIMEOUT_MILLIS = 60_000L
    }
}

fun AgentTokenEntity.info() = AgentTokenInfo(id, name, tokenHint, scopeSet.sorted(), createdAt, createdVia)
