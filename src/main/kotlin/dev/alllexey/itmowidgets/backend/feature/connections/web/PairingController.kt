package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionNotificationService
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.http.ClientAddress
import dev.alllexey.itmowidgets.backend.platform.security.ClientCookie
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Pairing (docs/contracts/connections.md#pairing): the client's anonymous side (create, claim) and the phone's (open,
 * approve, the app's ITMO.ID bearer only). A renewal is created on `ClientController`.
 */
@RestController
class PairingController(private val pairings: PairingService, private val notifications: ConnectionNotificationService) {
    @PostMapping("/api/pair/challenges")
    fun createChallenge(@RequestBody body: CreateChallengeRequest, request: HttpServletRequest): ApiResponse<CreatedChallenge> =
        ApiResponse.success(pairings.create(body, ClientAddress.of(request)))

    /** The claim of a new connection pushes to the owner's devices; the web's sets the `iw_client` cookie instead of a token. */
    @PostMapping("/api/pair/challenges/{challengeId}/claim")
    fun claim(@PathVariable challengeId: UUID, @RequestBody body: ClaimPairingRequest): ResponseEntity<ApiResponse<PairingClaimResponse>> =
        when (val claim = pairings.claim(challengeId, body.claimSecret)) {
            is PairingClaim.Waiting -> ResponseEntity.status(HttpStatus.ACCEPTED).body(
                ApiResponse.success(PairingClaimResponse(if (claim.opened) PairingClaimStatus.OPENED else PairingClaimStatus.PENDING)),
            )

            is PairingClaim.Claimed -> {
                val connection = claim.connection
                if (claim.kind ==
                    PairKind.CONNECT
                ) {
                    notifications.notifyConnected(claim.userId, connection.id, connection.client, connection.name)
                }
                val cookie = claim.token?.takeIf { connection.client == ClientType.WEB }
                val response = PairingClaimResponse(
                    PairingClaimStatus.CLAIMED,
                    claim.token.takeIf { cookie == null },
                    connection,
                    claim.sealedBundle?.let(PairingService::encode),
                )
                ResponseEntity.ok().apply {
                    if (cookie != null) header(HttpHeaders.SET_COOKIE, ClientCookie.issue(cookie).toString())
                }.body(ApiResponse.success(response))
            }
        }

    @PostMapping("/api/users/me/pair/{code}/open")
    fun open(@PathVariable code: String, authentication: Authentication): ApiResponse<PairingPreview> =
        ApiResponse.success(pairings.open(authentication.phoneUser(), code))

    @PostMapping("/api/users/me/pair/{challengeId}/approve")
    fun approve(
        @PathVariable challengeId: UUID,
        @RequestBody request: ApprovePairingRequest,
        authentication: Authentication,
    ): ApiResponse<ApprovedPairing> =
        ApiResponse.success(ApprovedPairing(pairings.approve(authentication.phoneUser(), challengeId, request)))
}
