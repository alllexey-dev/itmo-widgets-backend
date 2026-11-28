package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.model.Grants
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionClientRepository
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.PairChallengeRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.time.Clock

/** A registry write: the entry as stored and how many live connections its suspension revoked. */
data class SavedClient(val client: ConnectionClient, val created: Boolean, val revokedConnections: Int)

/**
 * The registry of client apps (docs/contracts/connections.md#registry), cached in memory: loaded on first use, again
 * after every write commits and every minute, so another instance sees an admin change within a minute. Only the owner
 * writes it, through the admin routes; pairing and «Устройства» read it.
 */
@Service
class ConnectionClientRegistry(
    private val repository: ConnectionClientRepository,
    private val connections: ConnectionRepository,
    private val challenges: PairChallengeRepository,
    private val clock: Clock,
) {
    @Volatile private var cache: Map<String, ConnectionClient>? = null

    /** Every entry, oldest first. */
    fun all(): List<ConnectionClient> = clients().values.toList()

    fun find(id: String): ConnectionClient? = clients()[id]

    /**
     * The entry a stored row names; the foreign key keeps it present. A miss reloads once: another instance may have
     * registered it within the last minute.
     */
    fun get(id: String): ConnectionClient =
        find(id) ?: reload().let { checkNotNull(find(id)) { "Connection client $id is not registered" } }

    fun reload() {
        cache = repository.findAll().associateBy { it.id }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun refresh() {
        try {
            reload()
        } catch (error: Exception) {
            log.error("Connection client registry reload failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    /**
     * Inserts or replaces [client] with its grants as a whole, after [validate]. The kind of an existing entry never
     * changes: its connections are counted and routed by it. Suspending revokes every live connection of the client
     * and ends its pending challenges in the same transaction; the phones then end their ITMO sessions as after any
     * revocation. The cache reloads after the commit.
     */
    @Transactional
    fun save(client: ConnectionClient): SavedClient {
        validate(client)
        val existing = if (repository.lock(client.id)) repository.findAll().single { it.id == client.id } else null
        if (existing != null &&
            existing.kind != client.kind
        ) {
            throw InvalidRequestDataException("The kind of a registered client never changes")
        }
        val now = clock.instant()
        repository.upsert(client, now)
        var revoked = 0
        if (client.status == ClientStatus.SUSPENDED) {
            val legacyKind = ClientType.ofLegacyClientId(client.id)
            revoked = connections.revokeLiveOfClient(client.id, legacyKind, now)
            challenges.expireForClient(client.id, legacyKind)
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = reload()
            },
        )
        log.info("Connection client {} {}, {} live connections revoked", client.id, if (existing == null) "created" else "updated", revoked)
        val saved = client.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        return SavedClient(saved, existing == null, revoked)
    }

    private fun clients(): Map<String, ConnectionClient> = cache ?: synchronized(this) { cache ?: reload().let { checkNotNull(cache) } }

    companion object {
        const val MAX_NAME = 60
        const val MAX_URL = 200
        const val MAX_PURPOSE = 200
        const val MAX_PER_USER = 100
        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        private val KID = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val log = LoggerFactory.getLogger(ConnectionClientRegistry::class.java)

        /**
         * The rules of an entry (400 `invalid_request_data`): a slug id, a name of 1-60 characters, https links, a positive
         * cap, known grant ids each at most once, at least one `widgets:*` grant or `identity`, the grant policy of
         * [ConnectionClient.policyViolation], and keys only for a confidential client, each a distinct kid with a raw
         * 32-byte Ed25519 public key. A confidential client without keys is stored but cannot pair.
         */
        fun validate(client: ConnectionClient) {
            if (!ID.matches(client.id)) throw InvalidRequestDataException("id is 1-64 characters of a-z, 0-9 and -, not starting with -")
            client.returnUrl?.let { url(it, "returnUrl") }
            if (client.auth == ClientAuth.PUBLIC &&
                client.keys.isNotEmpty()
            ) {
                throw InvalidRequestDataException("A public client has no keys")
            }
            val kids = client.keys.map { it.kid }
            if (kids.size != kids.toSet().size) throw InvalidRequestDataException("Each key kid at most once")
            client.keys.forEach { key ->
                if (!KID.matches(key.kid)) throw InvalidRequestDataException("kid is 1-64 characters of A-Z, a-z, 0-9, ., _ and -")
                if (EdDsaJws.decode(key.publicKey)?.let(EdDsaJws::publicKey) == null) {
                    throw InvalidRequestDataException("publicKey is base64url without padding of a raw 32-byte Ed25519 key")
                }
            }
            if (PairingService.text(client.name, MAX_NAME, "name") != client.name) {
                throw InvalidRequestDataException("name must be 1-$MAX_NAME characters, trimmed")
            }
            client.homepage?.let { url(it, "homepage") }
            client.iconUrl?.let { url(it, "iconUrl") }
            if (client.maxPerUser != null && client.maxPerUser !in 1..MAX_PER_USER) {
                throw InvalidRequestDataException("maxPerUser must be 1 to $MAX_PER_USER or null")
            }
            val ids = client.grants.map { it.id }
            if (ids.size != ids.toSet().size) throw InvalidRequestDataException("Each grant at most once")
            if (!ids.all(Grants::isKnown)) throw InvalidRequestDataException("Unknown grant id")
            if (ids.none(Grants::isWidgets)) throw InvalidRequestDataException("A client needs at least one widgets grant or identity")
            client.grants.forEach { grant ->
                val purpose = grant.purpose ?: return@forEach
                if (PairingService.text(purpose, MAX_PURPOSE, "purpose") != purpose) {
                    throw InvalidRequestDataException("purpose must be 1-$MAX_PURPOSE characters, trimmed, or null")
                }
            }
            client.policyViolation(ids)?.let { throw InvalidRequestDataException(it) }
        }

        private fun url(value: String, field: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            if (value.length > MAX_URL || uri == null || uri.scheme != "https" || uri.host.isNullOrEmpty()) {
                throw InvalidRequestDataException("$field must be an https URL of at most $MAX_URL characters")
            }
        }
    }
}
