package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminServiceCredential
import dev.alllexey.itmowidgets.backend.feature.admin.web.ServiceCredentialRequest
import dev.alllexey.itmowidgets.backend.feature.app.service.AppConfig
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.credentials.model.CredentialSource
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialKind
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store commits in its own transactions, so this class commits too and removes its users afterwards. */
@Import(
    AdminSystemService::class,
    AppVersionSettings::class,
    AdminAccess::class,
    AdminAuditService::class,
    AdminUserSummaries::class,
    ServiceCredentialStore::class,
    AdminCredentialsServiceTest.TestConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminCredentialsServiceTest @Autowired constructor(
    private val service: AdminSystemService,
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppConfig::class)
    class TestConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private lateinit var admin: UUID
    private lateinit var moderator: UUID

    @BeforeEach
    fun fixture() {
        resetCredentials()
        TransactionTemplate(manager).executeWithoutResult {
            admin = user(ADMIN_ISU, "Synthetic admin", "ADMIN")
            moderator = user(MODERATOR_ISU, "Synthetic moderator", "MODERATOR")
        }
    }

    @AfterEach
    fun cleanup() {
        resetCredentials()
        // Other classes share the database and count every user and audit row.
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (?, ?)", admin, moderator)
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (?, ?)", admin, moderator)
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", admin, moderator)
    }

    @Test
    fun `credentials lists every row in declaration order without values`() {
        jdbc.update(
            "UPDATE service_credentials SET value = 'synthetic-stored-cookie-value', status = 'OK', updated_source = 'SEED' " +
                "WHERE key = 'ISU_KEYCLOAK_IDENTITY'",
        )

        val credentials = service.credentials(admin)

        assertEquals(ServiceCredential.entries, credentials.map { it.key })
        assertEquals(ServiceCredential.entries.map { it.kind }, credentials.map { it.kind })
        assertEquals(listOf(true, false, false, true, true), credentials.map { it.replaceable })
        assertEquals(listOf(false, false, false, true, false), credentials.map { it.present })
        val cookie = credentials.byKey(ServiceCredential.ISU_KEYCLOAK_IDENTITY)
        assertEquals(ServiceCredentialKind.COOKIE, cookie.kind)
        assertEquals(ServiceCredentialStatus.OK, cookie.status)
        assertEquals(CredentialSource.SEED, cookie.updatedSource)
        assertNull(cookie.updatedByIsu)
        assertNull(cookie.updatedByName)
        assertFalse(credentials.toString().contains("synthetic-stored"))
    }

    @Test
    fun `expiresSoon uses the window of each credential`() {
        for ((expiresIn, expected) in listOf(Duration.ofDays(13) to true, Duration.ofDays(15) to false)) {
            expire(ServiceCredential.ISU_KEYCLOAK_IDENTITY, expiresIn)
            assertEquals(expected, service.credentials(admin).byKey(ServiceCredential.ISU_KEYCLOAK_IDENTITY).expiresSoon, "$expiresIn")
        }
        for ((expiresIn, expected) in listOf(Duration.ofHours(23) to true, Duration.ofHours(25) to false)) {
            expire(ServiceCredential.MY_ITMO_REFRESH_TOKEN, expiresIn)
            assertEquals(expected, service.credentials(admin).byKey(ServiceCredential.MY_ITMO_REFRESH_TOKEN).expiresSoon, "$expiresIn")
        }
        expire(ServiceCredential.MY_ITMO_ACCESS_TOKEN, Duration.ofMinutes(1))
        val access = service.credentials(admin).byKey(ServiceCredential.MY_ITMO_ACCESS_TOKEN)
        assertEquals(NOW.plus(Duration.ofMinutes(1)), access.expiresAt)
        assertFalse(access.expiresSoon)
    }

    @Test
    fun `an admin replaces a credential once audited and sees who did it`() {
        val credentials = service.replaceCredential(
            admin,
            ServiceCredential.ISU_KEYCLOAK_IDENTITY,
            ServiceCredentialRequest("  synthetic-admin-cookie-value  "),
        )

        val cookie = credentials.byKey(ServiceCredential.ISU_KEYCLOAK_IDENTITY)
        assertTrue(cookie.present)
        assertEquals(ServiceCredentialStatus.UNKNOWN, cookie.status)
        assertEquals(CredentialSource.ADMIN, cookie.updatedSource)
        assertEquals(ADMIN_ISU, cookie.updatedByIsu)
        assertEquals("Synthetic admin", cookie.updatedByName)
        assertEquals(NOW, cookie.updatedAt)
        assertEquals(
            "synthetic-admin-cookie-value",
            jdbc.queryForObject("SELECT value FROM service_credentials WHERE key = 'ISU_KEYCLOAK_IDENTITY'", String::class.java),
        )
        assertEquals(listOf("SERVICE_CREDENTIAL_REPLACED credential:ISU_KEYCLOAK_IDENTITY null"), audit())
    }

    @Test
    fun `the Gemini key is a replaceable API key replaced once audited`() {
        val listed = service.credentials(admin).byKey(ServiceCredential.GEMINI_API_KEY)
        assertEquals(ServiceCredentialKind.API_KEY, listed.kind)
        assertTrue(listed.replaceable)
        assertFalse(listed.present)
        assertEquals(ServiceCredentialStatus.MISSING, listed.status)
        assertFalse(listed.expiresSoon)
        val myItmoBefore = rows().filter { (it["key"] as String).startsWith("MY_ITMO_") }
        val key = "AIza" + "0".repeat(35)

        val credentials = service.replaceCredential(admin, ServiceCredential.GEMINI_API_KEY, ServiceCredentialRequest(key))

        val gemini = credentials.byKey(ServiceCredential.GEMINI_API_KEY)
        assertTrue(gemini.present)
        assertEquals(ServiceCredentialStatus.UNKNOWN, gemini.status)
        assertEquals(CredentialSource.ADMIN, gemini.updatedSource)
        assertEquals(ADMIN_ISU, gemini.updatedByIsu)
        assertNull(gemini.expiresAt)
        assertFalse(gemini.expiresSoon)
        assertFalse(credentials.toString().contains(key))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM service_credentials WHERE key = 'GEMINI_API_KEY' AND value = ?",
                Int::class.java,
                key,
            ),
        )
        assertEquals(listOf("SERVICE_CREDENTIAL_REPLACED credential:GEMINI_API_KEY null"), audit())
        assertEquals(myItmoBefore, rows().filter { (it["key"] as String).startsWith("MY_ITMO_") })
    }

    @Test
    fun `a credential issued by a refresh is not replaceable`() {
        val before = rows()

        assertFailsWith<InvalidRequestDataException> {
            service.replaceCredential(
                admin,
                ServiceCredential.MY_ITMO_ACCESS_TOKEN,
                ServiceCredentialRequest("synthetic-access-token-value"),
            )
        }

        assertEquals(before, rows())
        assertEquals(emptyList(), audit())
    }

    @Test
    fun `malformed values are refused without echoing them`() {
        val before = rows()
        for (value in listOf(
            "synthetic cookie with a space",
            "synthetic-cookie;Path=/value",
            "синтетическое-значение-куки",
            "s".repeat(19),
            "s".repeat(8193),
        )) {
            val error = assertFailsWith<InvalidRequestDataException> {
                service.replaceCredential(admin, ServiceCredential.ISU_KEYCLOAK_IDENTITY, ServiceCredentialRequest(value))
            }
            assertFalse(error.message.orEmpty().contains(value), value.take(30))
        }
        assertEquals(before, rows())
        assertEquals(emptyList(), audit())
    }

    @Test
    fun `a moderator can neither read nor replace credentials`() {
        assertFailsWith<PermissionDeniedException> { service.credentials(moderator) }
        assertFailsWith<PermissionDeniedException> {
            service.replaceCredential(
                moderator,
                ServiceCredential.ISU_KEYCLOAK_IDENTITY,
                ServiceCredentialRequest("synthetic-moderator-cookie"),
            )
        }
        assertNull(jdbc.queryForObject("SELECT value FROM service_credentials WHERE key = 'ISU_KEYCLOAK_IDENTITY'", String::class.java))
    }

    private fun List<AdminServiceCredential>.byKey(key: ServiceCredential) = single { it.key == key }

    private fun expire(credential: ServiceCredential, within: Duration) {
        jdbc.update("UPDATE service_credentials SET expires_at = ? WHERE key = ?", Timestamp.from(NOW.plus(within)), credential.name)
    }

    private fun rows(): List<Map<String, Any?>> = jdbc.queryForList(
        "SELECT key, value IS NOT NULL AS present, status, updated_at, updated_by, updated_source FROM service_credentials ORDER BY key",
    )

    private fun audit(): List<String> = jdbc.queryForList(
        "SELECT action || ' ' || target || ' ' || coalesce(details, 'null') FROM admin_audit WHERE actor_id = ?",
        String::class.java,
        admin,
    )

    private fun user(isu: Int, name: String, role: String): UUID = UUID.randomUUID().also { id ->
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, ?, ?)", id, isu, name)
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id)
        jdbc.update("INSERT INTO user_roles (user_id, role, granted_at) VALUES (?, ?, ?)", id, role, Timestamp.from(NOW))
    }

    private fun resetCredentials() {
        jdbc.update(
            """
            UPDATE service_credentials SET value = NULL, expires_at = NULL, status = 'MISSING', last_used_at = NULL,
                last_renewed_at = NULL, last_error_at = NULL, last_error = NULL, updated_by = NULL, updated_source = NULL
            """.trimIndent(),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2031-03-16T10:00:00Z")
        const val ADMIN_ISU = 962201
        const val MODERATOR_ISU = 962202
    }
}
