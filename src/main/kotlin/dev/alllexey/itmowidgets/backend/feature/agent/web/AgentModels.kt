package dev.alllexey.itmowidgets.backend.feature.agent.web

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenOrigin
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** A token as its owner sees it; never the token itself. */
data class AgentTokenInfo(
    val id: UUID,
    val name: String,
    val hint: String,
    val scopes: List<AgentScope>,
    val createdAt: Instant,
    val createdVia: AgentTokenOrigin,
)

/** Returned once; only the SHA-256 of [token] is stored. */
data class CreatedAgentToken(val token: String, val info: AgentTokenInfo)

data class CreateAgentTokenRequest(val name: String, val scopes: List<AgentScope>)

/** Who an agent token belongs to. */
data class AgentIdentity(val isu: Int, val tokenId: UUID, val name: String, val scopes: List<AgentScope>)

/** Whether self-hosted clients are on for the caller. */
data class AgentAccess(val enabled: Boolean)

/** One event: [type] and [payload] are the push envelope of the same message, [id] its idempotency key. */
data class AgentEvent(
    val seq: Long,
    val id: String,
    val type: String,
    val occurredAt: Instant,
    val expiresAt: Instant,
    @get:Schema(implementation = Map::class, description = "The payload of the push of the same type, nulls omitted")
    val payload: JsonNode,
)

data class AgentEventsPage(val events: List<AgentEvent>, val nextCursor: Long, val hasMore: Boolean, val gap: Boolean)
