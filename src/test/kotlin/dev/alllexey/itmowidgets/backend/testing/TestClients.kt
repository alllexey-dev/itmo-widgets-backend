package dev.alllexey.itmowidgets.backend.testing

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientDeployment
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientGrant
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.model.Grants
import java.time.Instant

/** The registry entries V39 seeds, for tests without a database; `ConnectionClientRegistryTest` pins them to the seed. */
object TestClients {
    val AGENT = ConnectionClient(
        "itmo-widgets-agent", ClientType.AGENT, "ITMO.Widgets agent", null, null, ClientDeployment.SELF_HOSTED, ClientAuth.PUBLIC,
        ClientStatus.ACTIVE, null,
        listOf(
            ClientGrant("widgets:sport", required = false, requestedByDefault = true, purpose = null),
            ClientGrant("widgets:friends:read", required = false, requestedByDefault = false, purpose = null),
            ClientGrant("widgets:schedule:write", required = false, requestedByDefault = false, purpose = null),
            ClientGrant(Grants.ITMO_REFRESH, required = true, requestedByDefault = true, purpose = "Запись на физкультуру через my.itmo"),
        ),
        Instant.EPOCH, Instant.EPOCH,
    )
    val MCP = ConnectionClient(
        "itmo-mcp", ClientType.MCP, "ITMO MCP", null, null, ClientDeployment.SELF_HOSTED, ClientAuth.PUBLIC, ClientStatus.ACTIVE, null,
        listOf(
            ClientGrant("widgets:reviews:read", required = false, requestedByDefault = true, purpose = null),
            ClientGrant("widgets:links:read", required = false, requestedByDefault = true, purpose = null),
            ClientGrant("widgets:sport:queue", required = false, requestedByDefault = false, purpose = null),
            ClientGrant(
                Grants.ITMO_REFRESH,
                required = false,
                requestedByDefault = true,
                purpose = "Расписание, зачётка, физкультура и заявки в my.itmo",
            ),
            ClientGrant(
                Grants.ITMO_SSO,
                required = false,
                requestedByDefault = false,
                purpose = "Баллы БАРС и вход в другие сервисы ИТМО без повторной привязки",
            ),
        ),
        Instant.EPOCH, Instant.EPOCH,
    )
    val WEB = ConnectionClient(
        "web", ClientType.WEB, "ITMO.Widgets в браузере", null, null, ClientDeployment.HOSTED, ClientAuth.PUBLIC, ClientStatus.ACTIVE, null,
        listOf(ClientGrant("widgets:user", required = true, requestedByDefault = true, purpose = null)),
        Instant.EPOCH, Instant.EPOCH,
    )
    val SEEDED = listOf(AGENT, MCP, WEB)

    fun of(kind: ClientType): ConnectionClient = SEEDED.single { it.kind == kind }
}
