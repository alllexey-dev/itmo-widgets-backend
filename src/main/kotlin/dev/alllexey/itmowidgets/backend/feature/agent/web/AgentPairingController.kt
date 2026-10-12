package dev.alllexey.itmowidgets.backend.feature.agent.web

import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionNotificationService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.NewPairing
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.http.ClientAddress
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * The target's side of pairing: anonymous creation and polling for a new agent, and a new ITMO session for a connected
 * agent with its token. The phone's side is `PairingController`.
 */
@RestController
class AgentPairingController(private val pairings: PairingService, private val notifications: ConnectionNotificationService) {
    @PostMapping("/api/pair/challenges")
    fun createPairing(@RequestBody body: CreatePairingRequest, request: HttpServletRequest): ApiResponse<CreatedPairing> =
        ApiResponse.success(
            pairings.create(
                body.kind,
                body.keyCommitment,
                body.name,
                body.version,
                request.getHeader(HttpHeaders.USER_AGENT),
                ClientAddress.of(request),
            ).wire(),
        )

    /** The poll that claims an approval answers once with the bundle and the token, then pushes to the owner's devices. */
    @GetMapping("/api/pair/challenges/{id}")
    fun pollPairing(@PathVariable id: UUID, @RequestHeader(POLL_SECRET_HEADER) pollSecret: String): ApiResponse<PairingPoll> =
        ApiResponse.success(
            when (val claim = pairings.poll(id, pollSecret)) {
                PairingClaim.Pending -> PairingPoll(PairingPollStatus.PENDING, null, null)

                PairingClaim.Opened -> PairingPoll(PairingPollStatus.OPENED, null, null)

                PairingClaim.Rejected -> PairingPoll(PairingPollStatus.REJECTED, null, null)

                PairingClaim.Expired -> PairingPoll(PairingPollStatus.EXPIRED, null, null)

                is PairingClaim.Approved -> {
                    notifications.notifyConnected(claim.userId, claim.connectionId, claim.kind, claim.targetName, null)
                    PairingPoll(
                        PairingPollStatus.APPROVED,
                        PairingService.encode(claim.sealedBundle),
                        claim.agentToken?.let { CreatedAgentToken(it.token, it.entity.info()) },
                    )
                }
            },
        )

    @PostMapping("/api/agent/pair/session")
    fun createSessionPairing(
        @RequestBody body: CreateSessionPairingRequest,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ApiResponse<CreatedPairing> {
        val agent = authentication as? AgentAuthentication ?: throw AccessDeniedException("Agent token required")
        return ApiResponse.success(pairings.createSession(agent.tokenId, body.keyCommitment, ClientAddress.of(request)).wire())
    }

    private fun NewPairing.wire() = CreatedPairing(id, code, pollSecret, expiresAt)

    companion object {
        const val POLL_SECRET_HEADER = "X-Poll-Secret"
    }
}
