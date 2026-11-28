package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.ConnectionClientAdmin
import dev.alllexey.itmowidgets.backend.feature.admin.web.ConnectionClientGrantAdmin
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientDeployment
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.Grants
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionClientRepository
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionClientRegistry
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The registry's admin routes on real PostgreSQL, committed: the role, the audit in the write's transaction, suspension. */
@Import(
    AdminConnectionClientsService::class,
    AdminAccess::class,
    AdminAuditService::class,
    ConnectionClientRegistry::class,
    ConnectionClientRepository::class,
    AdminConnectionClientsServiceTest.TimeConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminConnectionClientsServiceTest @Autowired constructor(
    private val service: AdminConnectionClientsService,
    private val registry: ConnectionClientRegistry,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private val users = mutableListOf<UUID>()

    @AfterEach
    fun cleanup() {
        users.forEach {
            jdbc.update("DELETE FROM admin_audit WHERE actor_id = ?", it)
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", it)
            jdbc.update("DELETE FROM users WHERE id = ?", it)
        }
        jdbc.update("DELETE FROM connection_clients WHERE id LIKE 'synthetic-%'")
        registry.reload()
    }

    @Test
    fun `only an admin reads and writes the registry`() {
        val student = user(null)
        val moderator = user("MODERATOR")
        for (caller in listOf(student, moderator)) {
            assertFailsWith<PermissionDeniedException> { service.list(caller) }
            assertFailsWith<PermissionDeniedException> { service.update(caller, ENTRY.id, ENTRY) }
        }
        assertEquals(0L, auditCount())
        val admin = user("ADMIN")
        assertEquals(listOf("itmo-mcp", "itmo-widgets-agent", "web"), service.list(admin).map { it.id }.sorted())
        val mcp = service.list(admin).single { it.id == "itmo-mcp" }
        assertEquals(
            listOf("widgets:reviews:read", "widgets:links:read", "widgets:sport:queue", Grants.ITMO_REFRESH, Grants.ITMO_SSO),
            mcp.grants.map {
                it.id
            },
        )
    }

    @Test
    fun `a write is audited once per change and an unchanged entry records nothing`() {
        val admin = user("ADMIN")
        assertEquals(ENTRY, service.update(admin, ENTRY.id, ENTRY))
        assertEquals(ENTRY, service.list(admin).single { it.id == ENTRY.id })
        assertEquals(ENTRY, service.update(admin, ENTRY.id, ENTRY))
        val renamed = ENTRY.copy(name = "Synthetic 2")
        service.update(admin, ENTRY.id, renamed)
        assertEquals(
            listOf(
                "CONNECTION_CLIENT_CHANGED connection-client:synthetic-admin created, status active",
                "CONNECTION_CLIENT_CHANGED connection-client:synthetic-admin updated, status active",
            ),
            jdbc.queryForList(
                "SELECT action || ' ' || target || ' ' || details FROM admin_audit WHERE actor_id = ? ORDER BY details",
                String::class.java,
                admin,
            ),
        )

        assertFailsWith<InvalidRequestDataException> { service.update(admin, "synthetic-other", ENTRY) }
        assertFailsWith<InvalidRequestDataException> {
            service.update(
                admin,
                ENTRY.id,
                renamed.copy(
                    grants =
                    renamed.grants + ConnectionClientGrantAdmin("itmo:unknown", false, false, null),
                ),
            )
        }
        assertFailsWith<InvalidRequestDataException> {
            service.update(
                admin,
                ENTRY.id,
                renamed.copy(
                    deployment = ClientDeployment.HOSTED,
                    grants =
                    renamed.grants + ConnectionClientGrantAdmin(Grants.ITMO_SSO, false, false, null),
                ),
            )
        }
        assertFailsWith<InvalidRequestDataException> { service.update(admin, ENTRY.id, renamed.copy(kind = ClientType.WEB)) }
        assertEquals(2L, auditCount())
    }

    @Test
    fun `suspending a client revokes its live connections and says how many`() {
        val admin = user("ADMIN")
        service.update(admin, ENTRY.id, ENTRY)
        val owner = user(null)
        val live = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO connections (id, user_id, client, client_id, name, scopes, created_at, approved_at) " +
                "VALUES (?, ?, 'APP', ?, 'Synthetic', 'links:read', now(), now())",
            live,
            owner,
            ENTRY.id,
        )
        service.update(admin, ENTRY.id, ENTRY.copy(status = ClientStatus.SUSPENDED))
        assertTrue(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM connections WHERE id = ?", Boolean::class.java, live) == true)
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit WHERE actor_id = ? AND details = 'updated, status suspended, 1 connections revoked'",
                Int::class.java,
                admin,
            ),
        )
        assertEquals(ClientStatus.SUSPENDED, registry.get(ENTRY.id).status)
    }

    private fun auditCount(): Long = checkNotNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit WHERE action = 'CONNECTION_CLIENT_CHANGED' AND target LIKE 'connection-client:synthetic-%'",
            Long::class.java,
        ),
    )

    private fun user(role: String?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, enrolled_at) VALUES (?, ?, now())", id, nextIsu.getAndIncrement())
        users.add(id)
        if (role != null) jdbc.update("INSERT INTO user_roles (user_id, role, granted_at) VALUES (?, ?, now())", id, role)
        return id
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-27T09:00:00Z")
        val nextIsu = AtomicInteger(979001)
        val ENTRY = ConnectionClientAdmin(
            "synthetic-admin", ClientType.APP, "Synthetic", "https://example.com", null, ClientDeployment.SELF_HOSTED, ClientAuth.PUBLIC,
            ClientStatus.ACTIVE, 2,
            listOf(
                ConnectionClientGrantAdmin("widgets:links:read", required = true, requestedByDefault = true, purpose = null),
                ConnectionClientGrantAdmin(Grants.ITMO_SSO, required = false, requestedByDefault = false, purpose = "Синтетика"),
            ),
            returnUrl = null,
            keys = emptyList(),
        )
    }
}
