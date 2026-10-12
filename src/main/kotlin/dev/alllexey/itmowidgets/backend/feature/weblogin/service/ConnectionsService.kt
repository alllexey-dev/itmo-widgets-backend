package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentReachability
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebLoginChallengeRepository
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebSessionRepository
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.ConnectionInfo
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.ConnectionKind
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** «Подключённые устройства»: the caller's live web sessions and agent tokens, and their revocation. No table of its own. */
@Service
class ConnectionsService(
    private val sessions: WebSessionRepository,
    private val sessionConfig: WebSessionConfig,
    private val challenges: WebLoginChallengeRepository,
    private val agentTokens: AgentTokenService,
    private val reachability: AgentReachability,
    private val clock: Clock,
) {
    /** Most recently used first; [currentSessionId] is the web session making the request, if any. */
    @Transactional(readOnly = true)
    fun list(userId: UUID, currentSessionId: UUID?): List<ConnectionInfo> {
        val now = clock.instant()
        val web = sessions.findActiveByUser(userId, now)
            .filter { now.isBefore(it.lastSeenAt.plus(sessionConfig.idleTimeout)) }
            .map {
                ConnectionInfo(
                    it.id, ConnectionKind.WEB_SESSION, null, it.userAgent, it.createdAt, it.lastSeenAt,
                    live = false, itmoSession = false, current = it.id == currentSessionId, scopes = null,
                )
            }
        val agents = agentTokens.activeTokens(userId).map {
            ConnectionInfo(
                it.id, ConnectionKind.AGENT, it.name, null, it.createdAt, listOfNotNull(it.lastUsedAt, it.lastPollAt).maxOrNull(),
                live = reachability.isLive(it), itmoSession = it.itmoSessionOk, current = false, scopes = it.scopeSet.sorted(),
            )
        }
        return (web + agents).sortedByDescending { it.lastUsedAt ?: it.createdAt }
    }

    /** Ends Backend access at once; an agent's pending session hand-offs end with its token. 404 for anything not the caller's. */
    @Transactional
    fun revoke(userId: UUID, id: UUID) {
        if (sessions.revokeById(id, userId, clock.instant()) == 1) {
            log.info("Web session {} of user {} revoked from connections", id, userId)
            return
        }
        if (!agentTokens.revoke(userId, id)) throw NotFoundException("Connection not found")
        challenges.expireForAgentToken(id)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ConnectionsService::class.java)
    }
}
