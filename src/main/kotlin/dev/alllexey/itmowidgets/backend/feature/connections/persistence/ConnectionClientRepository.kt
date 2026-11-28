package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientDeployment
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientGrant
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientKey
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The registry tables `connection_clients`, `connection_client_grants` (V39) and `connection_client_keys` (V40); three
 * queries read all of it. Also the used client assertion ids, `connection_client_assertions` (V40).
 */
@Repository
class ConnectionClientRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun findAll(): List<ConnectionClient> {
        val keys = jdbc.query(
            "SELECT client_id, kid, public_key FROM connection_client_keys ORDER BY client_id, created_at, kid",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getString("client_id") to ClientKey(rs.getString("kid"), rs.getString("public_key")) }
            .groupBy({ it.first }, { it.second })
        val grants = jdbc.query(
            "SELECT client_id, grant_id, required, requested_by_default, purpose FROM connection_client_grants ORDER BY client_id, position",
            emptyMap<String, Any>(),
        ) { rs, _ ->
            rs.getString("client_id") to ClientGrant(
                rs.getString("grant_id"),
                rs.getBoolean("required"),
                rs.getBoolean("requested_by_default"),
                rs.getString("purpose"),
            )
        }.groupBy({ it.first }, { it.second })
        return jdbc.query("SELECT * FROM connection_clients ORDER BY created_at, id", emptyMap<String, Any>()) { rs, _ ->
            val id = rs.getString("id")
            ConnectionClient(
                id = id,
                kind = checkNotNull(ClientType.fromWire(rs.getString("kind"))),
                name = rs.getString("name"),
                homepage = rs.getString("homepage"),
                iconUrl = rs.getString("icon_url"),
                deployment = checkNotNull(ClientDeployment.fromWire(rs.getString("deployment"))),
                auth = checkNotNull(ClientAuth.fromWire(rs.getString("auth"))),
                status = checkNotNull(ClientStatus.fromWire(rs.getString("status"))),
                maxPerUser = rs.getInt("max_per_user").takeUnless { rs.wasNull() },
                grants = grants[id].orEmpty(),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                returnUrl = rs.getString("return_url"),
                keys = keys[id].orEmpty(),
            )
        }
    }

    /** Locks the row of [id] for the rest of the transaction; false when there is none. */
    fun lock(id: String): Boolean =
        jdbc.queryForList("SELECT id FROM connection_clients WHERE id = :id FOR UPDATE", mapOf("id" to id), String::class.java).isNotEmpty()

    /**
     * Inserts or replaces the entry, its grants and its keys as a whole; `created_at` stays on an update, of a key too
     * while its `kid` and value stay.
     */
    fun upsert(client: ConnectionClient, now: Instant) {
        val at = now.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO connection_clients (id, kind, name, homepage, icon_url, deployment, auth, status, max_per_user, return_url,
                created_at, updated_at)
            VALUES (:id, :kind, :name, :homepage, :iconUrl, :deployment, :auth, :status, :maxPerUser, :returnUrl, :now, :now)
            ON CONFLICT (id) DO UPDATE SET kind = EXCLUDED.kind, name = EXCLUDED.name, homepage = EXCLUDED.homepage,
                icon_url = EXCLUDED.icon_url, deployment = EXCLUDED.deployment, auth = EXCLUDED.auth, status = EXCLUDED.status,
                max_per_user = EXCLUDED.max_per_user, return_url = EXCLUDED.return_url, updated_at = EXCLUDED.updated_at
            """,
            mapOf(
                "id" to client.id,
                "kind" to client.kind.wire,
                "name" to client.name,
                "homepage" to client.homepage,
                "iconUrl" to client.iconUrl,
                "deployment" to client.deployment.wire,
                "auth" to client.auth.wire,
                "status" to client.status.wire,
                "maxPerUser" to client.maxPerUser,
                "returnUrl" to client.returnUrl,
                "now" to at,
            ),
        )
        jdbc.update("DELETE FROM connection_client_grants WHERE client_id = :id", mapOf("id" to client.id))
        client.grants.forEachIndexed { position, grant ->
            jdbc.update(
                """
                INSERT INTO connection_client_grants (client_id, grant_id, required, requested_by_default, purpose, position)
                VALUES (:clientId, :grantId, :required, :byDefault, :purpose, :position)
                """,
                mapOf(
                    "clientId" to client.id,
                    "grantId" to grant.id,
                    "required" to grant.required,
                    "byDefault" to grant.requestedByDefault,
                    "purpose" to grant.purpose,
                    "position" to position,
                ),
            )
        }
        val kids = client.keys.map { it.kid }
        jdbc.update(
            "DELETE FROM connection_client_keys WHERE client_id = :id" + if (kids.isEmpty()) "" else " AND kid NOT IN (:kids)",
            mapOf("id" to client.id, "kids" to kids),
        )
        client.keys.forEach { key ->
            jdbc.update(
                """
                INSERT INTO connection_client_keys (client_id, kid, public_key, created_at) VALUES (:clientId, :kid, :publicKey, :now)
                ON CONFLICT (client_id, kid) DO UPDATE SET public_key = EXCLUDED.public_key,
                    created_at = CASE WHEN connection_client_keys.public_key = EXCLUDED.public_key
                        THEN connection_client_keys.created_at ELSE EXCLUDED.created_at END
                """,
                mapOf("clientId" to client.id, "kid" to key.kid, "publicKey" to key.publicKey, "now" to at),
            )
        }
    }

    /** Records the assertion id [jti] of [clientId] until [expiresAt]; false when it was used before (a replay). */
    fun useAssertion(clientId: String, jti: String, expiresAt: Instant): Boolean = jdbc.update(
        """
        INSERT INTO connection_client_assertions (client_id, jti, expires_at) VALUES (:clientId, :jti, :expiresAt)
        ON CONFLICT (client_id, jti) DO NOTHING
        """,
        mapOf("clientId" to clientId, "jti" to jti, "expiresAt" to expiresAt.atOffset(ZoneOffset.UTC)),
    ) == 1

    /** Assertion ids past their life can no longer be replayed: their assertions are expired anyway. */
    fun deleteAssertionsExpiredBefore(cutoff: Instant): Int = jdbc.update(
        "DELETE FROM connection_client_assertions WHERE expires_at < :cutoff",
        mapOf("cutoff" to cutoff.atOffset(ZoneOffset.UTC)),
    )
}
