package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.PairChallengeRepository
import dev.alllexey.itmowidgets.backend.feature.connections.web.ClientAppInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.ConnectionInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.InstallInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.connections.web.ScopeGrant
import dev.alllexey.itmowidgets.backend.feature.connections.web.UserDevices
import dev.alllexey.itmowidgets.backend.feature.push.model.PushProvider
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * «Устройства» (docs/contracts/connections.md#devices): the caller's connections and app installs, scopes, revocation,
 * the phone's report that it ended a revoked client's ITMO.ID session or took a live client's ITMO access back, an
 * install's sign-out and a client's own status. Logs carry ids only.
 */
@Service
class ConnectionService(
    private val connections: ConnectionRepository,
    private val challenges: PairChallengeRepository,
    private val reachability: ClientReachability,
    private val clients: ClientActivityService,
    private val devices: DeviceService,
    private val texts: ScopeTexts,
    private val registry: ConnectionClientRegistry,
    private val clock: Clock,
) {
    /** [currentInstall] is the calling app install (`X-Client` `install`), null for the web. */
    @Transactional(readOnly = true)
    fun devices(userId: UUID, currentInstall: UUID?): UserDevices {
        val pushDevices = devices.devicesByInstall(userId)
        val installs = clients.installs(userId).map { instance ->
            val device = pushDevices[instance.instanceId]
            InstallInfo(
                instance.instanceId,
                AppPlatform.valueOf(instance.kind.name),
                device?.deviceName,
                instance.os,
                instance.version,
                instance.firstSeenAt,
                instance.lastSeenAt,
                pushEnabled = device != null && device.pushProvider == PushProvider.FCM && device.alertsAllowed,
                signedOutAt = instance.pushSignedOutAt,
                sid = instance.itmoSid,
                current = instance.instanceId == currentInstall,
            )
        }
        val listed = connections.findListed(userId).map(::info).sortedByDescending { it.lastUsedAt ?: it.approvedAt }
        return UserDevices(listed, installs)
    }

    @Transactional(readOnly = true)
    fun get(connectionId: UUID): ConnectionInfo = info(connections.findById(connectionId).orElseThrow { notFound() })

    /**
     * Narrowing applies to any caller; widening only for [mayWiden] (the app). Always within the client's registry
     * scopes, or ones the connection already holds.
     */
    @Transactional
    fun updateScopes(userId: UUID, connectionId: UUID, scopes: Set<ConnectionScope>, mayWiden: Boolean): ConnectionInfo {
        val connection = live(userId, connectionId)
        val allowed = registry.get(connection.registryId).scopes + connection.scopeSet
        if (scopes.isEmpty() || !allowed.containsAll(scopes)) {
            throw InvalidRequestDataException("scopes must be non-empty and within ${connection.client.wire}'s allowed scopes")
        }
        if (!mayWiden && !connection.scopeSet.containsAll(scopes)) throw PermissionDeniedException("Scopes are widened from the app")
        connection.scopes = ConnectionScope.format(scopes)
        log.info("Connection {} of user {} scopes changed", connectionId, userId)
        return info(connection)
    }

    /** Ends Backend access at once and the connection's pending renewals. 404 for anything not the caller's or already ended. */
    @Transactional
    fun revoke(userId: UUID, connectionId: UUID) {
        val connection = live(userId, connectionId)
        connection.revokedAt = clock.instant()
        challenges.expireForConnection(connectionId)
        log.info("Connection {} of user {} revoked", connectionId, userId)
    }

    /** The phone ended a revoked connection's ITMO.ID session; Backend forgets its id. Repeating it is a no-op. */
    @Transactional
    fun itmoEnded(userId: UUID, connectionId: UUID) {
        val connection = connections.findById(connectionId).orElse(null)?.takeIf { it.userId == userId } ?: throw notFound()
        if (connection.revokedAt == null) throw BusinessRuleException("Disconnect the client before ending its ITMO.ID session")
        if (connection.itmoEndedAt == null) connection.itmoEndedAt = clock.instant()
        connection.itmoSid = null
        log.info("Connection {} of user {} ITMO.ID session ended", connectionId, userId)
    }

    /**
     * The phone ended a live connection's ITMO.ID session («Забрать доступ к ИТМО»): the connection stays, with no ITMO
     * grant and no session until a renewal brings them back. 409 for a revoked connection, 404 for anything not the
     * caller's. Repeating it is a no-op.
     */
    @Transactional
    fun itmoClosed(userId: UUID, connectionId: UUID) {
        val connection = connections.findById(connectionId).orElse(null)?.takeIf { it.userId == userId } ?: throw notFound()
        if (connection.revokedAt != null) throw BusinessRuleException("The client is disconnected; report the ended session instead")
        connection.itmoStatus = ItmoStatus.CLOSED
        connection.itmoGrants = emptyArray()
        if (connection.itmoSid != null) connection.itmoEndedAt = clock.instant()
        connection.itmoSid = null
        connection.itmoExpiresAt = null
        log.info("Connection {} of user {} ITMO access taken back", connectionId, userId)
    }

    /** `PUT /api/client/status`: the ITMO session state at once, the version when it is sent. */
    @Transactional
    fun reportStatus(connectionId: UUID, itmo: ItmoStatus, version: String?) {
        val connection = connections.findById(connectionId).orElseThrow { notFound() }
        connection.itmoStatus = itmo
        if (version != null) connection.version = PairingService.text(version, PairingService.MAX_VERSION, "version")
    }

    /**
     * Signs another install of the caller out of pushes; its ITMO.ID session is the phone's to end. An install whose
     * registration this dropped is marked (`InstallInfo.signedOutAt`) until it registers again; one that had no
     * registration keeps its state, so the mark always means "turned off from another device".
     */
    @Transactional
    fun signOutInstall(userId: UUID, instanceId: UUID, currentInstall: UUID?) {
        if (instanceId == currentInstall) throw InvalidRequestDataException("Sign this install out in the app instead")
        val known = clients.installs(userId).any { it.instanceId == instanceId }
        val dropped = devices.unregisterInstall(userId, instanceId) > 0
        if (!dropped && !known) throw NotFoundException("Install not found")
        if (dropped) clients.markPushSignedOut(userId, instanceId)
        log.info("Install {} of user {} signed out of pushes", instanceId, userId)
    }

    /** The latest request any connection of the user made, live or revoked; null when none was used. */
    @Transactional(readOnly = true)
    fun lastUsed(userId: UUID): Instant? = connections.findLastUsed(userId)

    /** [lastUsed] of every user with a used connection, in one query. */
    @Transactional(readOnly = true)
    fun lastUsedByUser(): Map<UUID, Instant> = connections.findLastUsedByUser().associate { it.userId to it.lastUsed }

    @Transactional(readOnly = true)
    fun countCreatedSince(client: ClientType, since: Instant): Long = connections.countByClientAndCreatedAtGreaterThanEqual(client, since)

    @Transactional(readOnly = true)
    fun namesOf(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap() else connections.findAllById(ids).associate { it.id to it.name }

    /** [ConnectionInfo.scopes]: the registry's scopes of the client in order, then any it holds that the registry dropped. */
    fun info(connection: ConnectionEntity): ConnectionInfo {
        val client = registry.get(connection.registryId)
        val held = connection.scopeSet
        val listed = (client.scopes + held.sorted()).distinct()
        return info(connection, client, listed.map { ScopeGrant(texts.info(it), it in held) })
    }

    private fun info(connection: ConnectionEntity, client: ConnectionClient, scopes: List<ScopeGrant>) = ConnectionInfo(
        connection.id,
        connection.client,
        connection.name,
        connection.version,
        connection.platform,
        scopes,
        connection.createdAt,
        connection.approvedAt,
        listOfNotNull(connection.lastUsedAt, connection.lastPollAt).maxOrNull(),
        reachability.isLive(connection),
        connection.itmoSid?.let { ItmoSession(it, checkNotNull(connection.itmoExpiresAt)) },
        connection.itmoStatus,
        connection.revokedAt,
        client.id,
        appInfo(client),
        connection.itmoGrants.toList(),
    )

    private fun live(userId: UUID, connectionId: UUID): ConnectionEntity = connections.findById(connectionId).orElse(null)
        ?.takeIf { it.userId == userId && it.revokedAt == null }
        ?: throw notFound()

    private fun notFound() = NotFoundException("Connection not found")

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionService::class.java)

        /** A registry entry as the phone shows it; no client proves itself yet, so none is verified. */
        fun appInfo(client: ConnectionClient) =
            ClientAppInfo(client.id, client.name, client.homepage, client.iconUrl, client.deployment, false)
    }
}
