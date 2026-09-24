package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.core.model.GroupData
import java.time.Instant
import java.util.UUID

/** Returned once to the browser that asked; only the hash of [pollSecret] is stored. */
data class CreatedChallenge(val id: UUID, val code: String, val pollSecret: String, val expiresAt: Instant)

/** What the app shows before the user approves a browser. */
data class WebLoginPreview(val challengeId: UUID, val userAgent: String?, val createdAt: Instant, val expiresAt: Instant)

enum class WebLoginPollStatus { PENDING, APPROVED, EXPIRED }

data class WebLoginPoll(val status: WebLoginPollStatus)

sealed interface ClaimResult {
    data object Pending : ClaimResult
    data object Expired : ClaimResult
    data class Approved(val sessionToken: String) : ClaimResult
}

/** The signed-in web user: own public identity and roles. */
data class WebMe(
    val isu: Int,
    val name: String,
    val pictureUrl: String?,
    val groups: List<GroupData>,
    val roles: List<String>,
)
