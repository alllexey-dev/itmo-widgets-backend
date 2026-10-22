package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventsSlice
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.http.ClientAddress
import dev.alllexey.itmowidgets.backend.platform.security.ClientCookie
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import tools.jackson.databind.json.JsonMapper

/** A connected client's own routes (docs/contracts/connections.md#client-routes); the route policy decides who reaches which. */
@RestController
@RequestMapping("/api/client")
class ClientController(
    private val connections: ConnectionService,
    private val pairings: PairingService,
    private val polls: ClientPollService,
    private val users: UserService,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping("/me")
    fun me(authentication: Authentication): ApiResponse<ClientMe> {
        val client = authentication.client()
        return ApiResponse.success(ClientMe(users.isuOf(client.userId), connections.get(client.connectionId)))
    }

    /** The client's `unpair` or the web's sign-out: the same revocation as in the app; a second call is 401 like any other. */
    @DeleteMapping("/self")
    fun revokeSelf(authentication: Authentication): ResponseEntity<ApiResponse<Unit>> {
        val client = authentication.client()
        connections.revoke(client.userId, client.connectionId)
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, ClientCookie.clear().toString()).body(ApiResponse.success(Unit))
    }

    @PostMapping("/pair/renew")
    fun renew(
        @RequestBody body: RenewChallengeRequest,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ApiResponse<CreatedChallenge> =
        ApiResponse.success(pairings.renew(authentication.client().connectionId, body.keyCommitment, ClientAddress.of(request)))

    @PutMapping("/status")
    fun status(@RequestBody body: ClientStatusRequest, authentication: Authentication): ApiResponse<Unit> {
        connections.reportStatus(authentication.client().connectionId, body.itmo, body.version)
        return ApiResponse.success(Unit)
    }

    /** The agent's long-poll; `itmo` is its ITMO.ID session state (`ok`, `closed`, `none`). */
    @GetMapping("/events")
    fun events(
        @RequestParam(defaultValue = "0") after: Long,
        @RequestParam(defaultValue = "0") wait: Int,
        @RequestParam(defaultValue = "none") itmo: String,
        authentication: Authentication,
    ): DeferredResult<ApiResponse<ClientEventsPage>> {
        val result = DeferredResult<ApiResponse<ClientEventsPage>>(TIMEOUT_MILLIS)
        polls.poll(authentication.client(), after, wait, itmo, {
            result.setResult(ApiResponse.success(page(it)))
        }, { result.setErrorResult(it) })
        return result
    }

    private fun page(slice: ClientEventsSlice) = ClientEventsPage(
        slice.events.map { ClientEvent(it.seq, it.dedupKey, it.type, it.createdAt, it.expiresAt, jsonMapper.readTree(it.payload)) },
        slice.nextCursor,
        slice.hasMore,
        slice.gap,
    )

    private fun Authentication.client(): ConnectionAuthentication =
        this as? ConnectionAuthentication ?: throw AccessDeniedException("Connection token required")

    companion object {
        /** A backstop only: the poll answers itself after at most 30 seconds. */
        private const val TIMEOUT_MILLIS = 60_000L
    }
}
