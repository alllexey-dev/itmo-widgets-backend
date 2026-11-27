package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.ConnectionClientAdmin
import dev.alllexey.itmowidgets.backend.feature.admin.web.ConnectionClientGrantAdmin
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientGrant
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionClientRegistry
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The registry of connected client apps for the owner (docs/contracts/admin.md#connected-client-apps). A write that
 * changes an entry is audited once, in its transaction; repeating the same entry changes and records nothing.
 */
@Service
class AdminConnectionClientsService(
    private val access: AdminAccess,
    private val registry: ConnectionClientRegistry,
    private val audit: AdminAuditService,
) {
    fun list(adminId: UUID): List<ConnectionClientAdmin> {
        access.requireAdmin(adminId)
        return registry.all().map(::view)
    }

    @Transactional
    fun update(adminId: UUID, id: String, request: ConnectionClientAdmin): ConnectionClientAdmin {
        access.requireAdmin(adminId)
        if (request.id != id) throw InvalidRequestDataException("The body's id must be the path's")
        val existing = registry.find(id)
        if (existing != null && view(existing) == request) return request
        val saved = registry.save(model(request, existing))
        val details = buildList {
            add(if (saved.created) "created" else "updated")
            add("status ${saved.client.status.wire}")
            if (saved.revokedConnections > 0) add("${saved.revokedConnections} connections revoked")
        }.joinToString(", ")
        audit.record(adminId, AdminAuditAction.CONNECTION_CLIENT_CHANGED, "connection-client:$id", details)
        return view(saved.client)
    }

    private fun view(client: ConnectionClient) = ConnectionClientAdmin(
        client.id,
        client.kind,
        client.name,
        client.homepage,
        client.iconUrl,
        client.deployment,
        client.auth,
        client.status,
        client.maxPerUser,
        client.grants.map { ConnectionClientGrantAdmin(it.id, it.required, it.requestedByDefault, it.purpose) },
    )

    private fun model(request: ConnectionClientAdmin, existing: ConnectionClient?) = ConnectionClient(
        id = request.id,
        kind = request.kind,
        name = request.name,
        homepage = request.homepage,
        iconUrl = request.iconUrl,
        deployment = request.deployment,
        auth = request.auth,
        status = request.status,
        maxPerUser = request.maxPerUser,
        grants = request.grants.map { ClientGrant(it.id, it.required, it.requestedByDefault, it.purpose) },
        createdAt = existing?.createdAt ?: Instant.EPOCH,
        updatedAt = existing?.updatedAt ?: Instant.EPOCH,
    )
}
