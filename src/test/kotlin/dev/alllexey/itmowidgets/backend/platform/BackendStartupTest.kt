package dev.alllexey.itmowidgets.backend.platform

import api.myitmo.MyItmoApi
import api.myitmo.model.IdValuePair
import api.myitmo.model.ResultResponse
import api.myitmo.model.other.TokenResponse
import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.SportSchedule
import api.myitmo.model.sport.TimeSlot
import api.myitmo.utils.AuthHelper
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.sun.net.httpserver.HttpServer
import dev.alllexey.itmowidgets.backend.Application
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoService
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.reviews.service.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.GeminiClient
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuClient
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportUpdateService
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.service.UserRegistrationService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.platform.migration.MigrationScripts
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.hibernate.tool.schema.spi.SchemaManagementException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.env.MapPropertySource
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.support.CronTrigger
import org.springframework.transaction.support.TransactionSynchronizationManager
import retrofit2.Call
import retrofit2.Response
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import api.myitmo.model.sport.SportLesson as ApiSportLesson

/** Full production application contexts; only external clients and wall-clock scheduling are replaced. */
class BackendStartupTest {
    @Test
    fun `full startup and restart preserve migrated schema rotated tokens and owner settings`() {
        val schema = newSchema()
        val firstFakes = ExternalFakes()
        lateinit var ownerId: UUID
        lateinit var firstHistory: List<MigrationStamp>
        start(schema, firstFakes).use { context ->
            assertHealthyHttp(context)
            assertSchema(context, schema)
            assertCatalog(context)
            assertRefreshOutcomes(context, RefreshOutcome("SUCCESS", received = 1, added = 1))
            assertEquals(1, firstFakes.scheduleRequests.get())
            assertCredential(context, "MY_ITMO_REFRESH_TOKEN", BOOTSTRAP, "SEED")
            assertCredential(context, "ISU_KEYCLOAK_IDENTITY", null, null)
            // Disabled AI summaries start without a model, a proxy or a key.
            val summaries = context.getBean(AiSummaryConfig::class.java)
            assertFalse(summaries.enabled)
            assertEquals("", summaries.model)
            assertEquals("", summaries.proxyHost)
            assertCredential(context, "GEMINI_API_KEY", null, null)
            // Without a seeded cookie the startup check neither logs in nor needs the network.
            verifyNoInteractions(firstFakes.isu)
            assertLegacyTokenTableUntouched(context)
            val client = context.getBean(MyItmoService::class.java).myItmo
            client.forceRefreshTokens()
            verify(firstFakes.auth).refreshTokens(BOOTSTRAP)
            assertRotatedTokens(context)

            val owner = context.getBean(UserRegistrationService::class.java).findOrCreateByIsu(OWNER_ISU)
            ownerId = owner.id
            context.getBean(UserService::class.java).updatePrivacySettings(
                owner,
                UserPrivacySettings(SharingVisibility.NOBODY, SharingVisibility.ALL, SharingVisibility.ALL),
            )
            context.getBean(JdbcTemplate::class.java).update(
                "UPDATE user_settings SET auto_sign_limit = 7 WHERE user_id = ?",
                ownerId,
            )
            firstHistory = history(context.getBean(JdbcTemplate::class.java))
            assertEquals(MigrationScripts.versions.map { it.version }, firstHistory.map { it.version })
            assertIdleReviewsSync(context)
            // A lease left by a crashed run must not survive the restart below.
            context.getBean(JdbcTemplate::class.java).update("UPDATE external_review_sync_state SET running_since = now()")
            firstFakes.assertNoExternalDelivery()
        }

        // New beans and connections, but the same disposable database schema and stale bootstrap seed.
        val restartedFakes = ExternalFakes()
        start(schema, restartedFakes).use { context ->
            assertHealthyHttp(context)
            assertSchema(context, schema)
            assertCatalog(context)
            assertEquals(firstHistory, history(context.getBean(JdbcTemplate::class.java)))
            assertRotatedTokens(context)
            assertCredential(context, "MY_ITMO_REFRESH_TOKEN", "synthetic-rotated-refresh", "ROTATION")
            assertLegacyTokenTableUntouched(context)
            val owner = context.getBean(UserRegistrationService::class.java).findOrCreateByIsu(OWNER_ISU)
            assertEquals(ownerId, owner.id)
            assertEquals(ownerId, owner.settings.userId)
            assertEquals(SharingVisibility.NOBODY, owner.settings.scheduleVisibility)
            assertEquals(SharingVisibility.ALL, owner.settings.sportVisibility)
            assertEquals(7, owner.settings.autoSignLimit)
            assertIdleReviewsSync(context)
            assertRefreshOutcomes(
                context,
                RefreshOutcome("SUCCESS", received = 1, added = 1),
                RefreshOutcome("SUCCESS", received = 1, added = 0),
            )
            assertEquals(1, restartedFakes.scheduleRequests.get())
            verifyNoInteractions(restartedFakes.auth)
            restartedFakes.assertNoExternalDelivery()
        }
    }

    @Test
    fun `upstream unavailability leaves HTTP available and an actual scheduled retry recovers`() {
        val fakes = ExternalFakes().apply { available = false }
        start(newSchema(), fakes).use { context ->
            assertHealthyHttp(context)
            val jdbc = context.getBean(JdbcTemplate::class.java)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons", Long::class.java))
            assertRefreshOutcomes(context, RefreshOutcome("FAILED", received = 0, added = 0, category = "NETWORK"))
            assertEquals(1, fakes.scheduleRequests.get())
            assertEquals(BOOTSTRAP, context.getBean(ServiceCredentialStore::class.java).myItmoSnapshot().refreshToken)

            fakes.available = true
            // Invoke the runnables registered by @Scheduled, without a timer, sleep, or private method access.
            fakes.runScheduled("checkOtherUpdates")
            fakes.runScheduled("checkLessonUpdates")

            assertHealthyHttp(context)
            assertCatalog(context)
            assertRefreshOutcomes(
                context,
                RefreshOutcome("FAILED", received = 0, added = 0, category = "NETWORK"),
                RefreshOutcome("SUCCESS", received = 1, added = 1),
            )
            assertEquals(2, fakes.scheduleRequests.get())
            assertEquals(BOOTSTRAP, context.getBean(ServiceCredentialStore::class.java).myItmoSnapshot().refreshToken)
            verifyNoInteractions(fakes.auth)
            fakes.assertNoExternalDelivery()
        }
    }

    @Test
    fun `bearer request authenticates through the real verifier and the servlet security chain`() {
        SyntheticJwks().use { jwks ->
            start(newSchema(), ExternalFakes(), jwks).use { context ->
                val anonymous = get(context, "/api/users/me/privacy", bearer = null)
                assertEquals(401, anonymous.statusCode())
                assertEquals(
                    "unauthorized",
                    context.getBean(ObjectMapper::class.java).readTree(anonymous.body()).get("error").get("code").asString(),
                )

                val response = get(context, "/api/users/me/privacy", bearer = jwks.token(OWNER_ISU))
                assertEquals(200, response.statusCode())
                val body = context.getBean(ObjectMapper::class.java).readTree(response.body())
                assertTrue(body.get("success").asBoolean())
                assertEquals(setOf("scheduleVisibility", "sportVisibility", "friendsVisibility"), body.get("data").propertyNames().toSet())
                val users = context.getBean(JdbcTemplate::class.java)
                    .queryForObject("SELECT count(*) FROM users WHERE isu = ?", Long::class.java, OWNER_ISU)
                assertEquals(1L, users)

                val forged = get(context, "/api/users/me/privacy", bearer = SyntheticJwks().use { it.token(OWNER_ISU) })
                assertEquals(401, forged.statusCode())
            }
        }
    }

    @Test
    fun `schema validation failure remains fatal before sport fallback can hide it`() {
        val schema = newSchema()
        start(schema, ExternalFakes()).use { assertSchema(it, schema) }
        val jdbc = isolatedJdbc(schema)
        val previousHistory = history(jdbc)
        jdbc.execute("ALTER TABLE users DROP COLUMN name")
        val fakes = ExternalFakes().apply { available = false }

        val error = assertFailsWith<Exception> { start(schema, fakes).use { } }

        assertTrue(
            generateSequence<Throwable>(error) { it.cause }.take(20)
                .any { it is SchemaManagementException },
            "Hibernate validation must stop the full application",
        )
        assertEquals(0, fakes.apiRequests.get())
        assertEquals(previousHistory, history(jdbc))
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'users' AND column_name = 'name'",
                Long::class.java,
                schema,
            ),
        )
        fakes.assertNoExternalDelivery()
    }

    /** [jwks] keeps the real [ItmoJwtVerifier], pointed at a loopback key set; without it the verifier is a mock. */
    private fun start(schema: String, fakes: ExternalFakes, jwks: SyntheticJwks? = null): ConfigurableApplicationContext {
        val postgres = PostgreSqlTestDatabase.container
        val app = SpringApplication(Application::class.java)
        app.setWebApplicationType(WebApplicationType.SERVLET)
        app.setRegisterShutdownHook(false)
        app.setLogStartupInfo(false)
        app.addInitializers(
            ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                // Defaults are too weak: application.properties and inherited environment must never select a server DB.
                context.environment.propertySources.addFirst(
                    MapPropertySource(
                        "isolated-startup-fixture",
                        mapOf(
                            "spring.datasource.url" to schemaUrl(schema),
                            "spring.datasource.username" to postgres.username,
                            "spring.datasource.password" to postgres.password,
                            "spring.datasource.hikari.jdbc-url" to schemaUrl(schema),
                            "spring.datasource.hikari.username" to postgres.username,
                            "spring.datasource.hikari.password" to postgres.password,
                            "spring.flyway.url" to schemaUrl(schema),
                            "spring.flyway.user" to postgres.username,
                            "spring.flyway.password" to postgres.password,
                            "spring.flyway.schemas" to schema,
                            "spring.flyway.default-schema" to schema,
                            "spring.jpa.properties.hibernate.default_schema" to schema,
                            "server.address" to "127.0.0.1",
                            "server.port" to "0",
                            "spring.main.banner-mode" to "off",
                            "firebase.key.path" to "/unused-startup-test-key",
                            "itmowidgets.my-itmo.refresh-token" to BOOTSTRAP,
                            "itmowidgets.app.version" to "2.3",
                            "itmowidgets.app.min-version" to "2.1",
                            "itmowidgets.app.note" to "",
                        ) + jwks?.properties().orEmpty(),
                    ),
                )
                context.beanFactory.registerSingleton("startupFixtureClassFilter", FixtureClassFilter())
                // Regular BFPP runs after configuration parsing, before any normal singleton/listener is created.
                context.addBeanFactoryPostProcessor(
                    BeanFactoryPostProcessor { beanFactory ->
                        val registry = beanFactory as DefaultListableBeanFactory
                        replaceBean(registry, "initializeFirebase", FirebaseApp::class.java, fakes.firebaseApp)
                        replaceBean(registry, "firebaseMessaging", FirebaseMessaging::class.java, fakes.messaging)
                        if (jwks == null) replaceBean(registry, "itmoJwtVerifier", ItmoJwtVerifier::class.java, fakes.verifier)
                        replaceBean(registry, "clock", Clock::class.java, CLOCK)
                        replaceBean(registry, "taskScheduler", TaskScheduler::class.java, fakes.scheduler)
                        replaceBean(registry, "httpIsuClient", IsuClient::class.java, fakes.isu)
                        replaceBean(registry, "httpGeminiClient", GeminiClient::class.java, fakes.gemini)
                        beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
                            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                                if (bean !is MyItmoService) return bean
                                val observed = spy(bean)
                                doAnswer { invocation ->
                                    invocation.callRealMethod()
                                    val initialized = invocation.mock as MyItmoService
                                    initialized.myItmo.api = fakes.api
                                    initialized.myItmo.authHelper = fakes.auth
                                    null
                                }.`when`(observed).onApplicationEvent(
                                    any(ContextRefreshedEvent::class.java) ?: ContextRefreshedEvent(context),
                                )
                                return observed
                            }
                        })
                    },
                )
            },
        )
        return app.run()
    }

    private fun <T : Any> replaceBean(factory: DefaultListableBeanFactory, name: String, type: Class<T>, value: T) {
        if (factory.containsBeanDefinition(name)) factory.removeBeanDefinition(name)
        factory.registerBeanDefinition(name, RootBeanDefinition(type).apply { instanceSupplier = java.util.function.Supplier { value } })
    }

    private fun assertHealthyHttp(context: ConfigurableApplicationContext) {
        val port = (context as ServletWebServerApplicationContext).webServer!!.port
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val mapper = context.getBean(ObjectMapper::class.java)
        for (path in listOf("version", "version-info")) {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/app/$path"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            val body = mapper.readTree(response.body())
            assertTrue(body.get("success").asBoolean())
            if (path == "version") {
                assertEquals("2.3", body.get("data").asString())
            } else {
                assertEquals("2.1", body.get("data").get("minVersion").asString())
                assertEquals("2.3", body.get("data").get("latestVersion").asString())
                assertEquals("", body.get("data").get("note").asString())
            }
        }
    }

    private fun get(context: ConfigurableApplicationContext, path: String, bearer: String?): HttpResponse<String> {
        val port = (context as ServletWebServerApplicationContext).webServer!!.port
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5)).GET()
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun assertSchema(context: ConfigurableApplicationContext, schema: String) {
        val flyway = context.getBean(Flyway::class.java)
        assertEquals(MigrationScripts.latest, flyway.info().current().version.toString())
        assertFalse(flyway.configuration.isBaselineOnMigrate)
        assertTrue(flyway.configuration.isCleanDisabled)
        assertTrue(flyway.configuration.isValidateOnMigrate)
        assertEquals("validate", context.getBean(EntityManagerFactory::class.java).properties["hibernate.hbm2ddl.auto"])
        assertEquals(schema, context.getBean(JdbcTemplate::class.java).queryForObject("SELECT current_schema()", String::class.java))
    }

    private fun assertCatalog(context: ConfigurableApplicationContext) {
        val jdbc = context.getBean(JdbcTemplate::class.java)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons", Long::class.java))
        assertEquals(
            "Synthetic section",
            jdbc.queryForObject("SELECT section_name FROM sport_lessons WHERE id = ?", String::class.java, LESSON_ID),
        )
        assertEquals(
            NOW,
            jdbc.queryForObject("SELECT last_seen_at FROM sport_lessons WHERE id = ?", { rs, _ ->
                rs.getObject(1, OffsetDateTime::class.java).toInstant()
            }, LESSON_ID),
        )
    }

    private fun assertRefreshOutcomes(context: ConfigurableApplicationContext, vararg expected: RefreshOutcome) {
        val actual = context.getBean(JdbcTemplate::class.java).query("SELECT * FROM sport_update_logs ORDER BY id") { row, _ ->
            assertEquals(NOW, row.getObject("update_timestamp", OffsetDateTime::class.java).toInstant())
            assertTrue(row.getLong("duration_millis") >= 0)
            assertEquals(0, row.getInt("updated_lessons"))
            assertEquals(0, row.getInt("skipped_lessons"))
            RefreshOutcome(
                row.getString("outcome"),
                row.getInt("received_lessons"),
                row.getInt("new_lessons_added"),
                row.getString("error_category"),
            )
        }
        assertEquals(expected.toList(), actual)
    }

    private fun assertIdleReviewsSync(context: ConfigurableApplicationContext) {
        val row = context.getBean(JdbcTemplate::class.java).queryForMap(
            "SELECT running_since, last_outcome FROM external_review_sync_state WHERE provider = 'REVIEWS_WORK_GD'",
        )
        assertEquals(mapOf<String, Any?>("running_since" to null, "last_outcome" to null), row)
    }

    private data class RefreshOutcome(val outcome: String, val received: Int, val added: Int, val category: String? = null)

    private fun assertRotatedTokens(context: ConfigurableApplicationContext) {
        val snapshot = context.getBean(ServiceCredentialStore::class.java).myItmoSnapshot()
        assertEquals("synthetic-rotated-access", snapshot.accessToken)
        assertEquals("synthetic-rotated-refresh", snapshot.refreshToken)
        assertEquals("synthetic-rotated-id", snapshot.idToken)
        assertEquals(NOW.toEpochMilli() + 3_600_000L, snapshot.accessExpiresAt)
        assertEquals(NOW.toEpochMilli() + 86_400_000L, snapshot.refreshExpiresAt)
    }

    private fun assertCredential(context: ConfigurableApplicationContext, key: String, value: String?, source: String?) {
        val row = context.getBean(JdbcTemplate::class.java).queryForMap(
            "SELECT value, status, updated_source FROM service_credentials WHERE key = ?",
            key,
        )
        assertEquals(value, row["value"])
        assertEquals(source, row["updated_source"])
        if (value == null) assertEquals("MISSING", row["status"])
    }

    /** The previous image reads my_itmo_storage after an image-only rollback; the current one never writes it. */
    private fun assertLegacyTokenTableUntouched(context: ConfigurableApplicationContext) {
        assertEquals(0L, context.getBean(JdbcTemplate::class.java).queryForObject("SELECT count(*) FROM my_itmo_storage", Long::class.java))
    }

    private fun history(jdbc: JdbcTemplate): List<MigrationStamp> = jdbc.query(
        "SELECT version, checksum, installed_rank, installed_on FROM flyway_schema_history WHERE type = 'SQL' AND success ORDER BY installed_rank",
    ) { rs, _ -> MigrationStamp(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toLocalDateTime()) }

    private data class MigrationStamp(val version: String, val checksum: Int, val rank: Int, val installedOn: LocalDateTime)

    private fun isolatedJdbc(schema: String): JdbcTemplate = PostgreSqlTestDatabase.container.let {
        JdbcTemplate(DriverManagerDataSource(schemaUrl(schema), it.username, it.password))
    }

    private fun schemaUrl(schema: String): String = PostgreSqlTestDatabase.container.jdbcUrl.let {
        it + (if ('?' in it) "&" else "?") + "currentSchema=$schema"
    }

    private fun newSchema() = "startup_" + UUID.randomUUID().toString().replace("-", "")

    /** Keep unrelated test @Configuration classes out of the real application's component scan. */
    private class FixtureClassFilter : TypeExcludeFilter() {
        private val root = BackendStartupTest::class.java.protectionDomain.codeSource.location.toExternalForm()
        override fun match(reader: MetadataReader, factory: MetadataReaderFactory): Boolean =
            reader.resource.url.toExternalForm().startsWith(root)
        override fun equals(other: Any?): Boolean = other is FixtureClassFilter && root == other.root
        override fun hashCode(): Int = root.hashCode()
    }

    /** A loopback JWKS endpoint with one synthetic RSA key; [token] signs an ITMO.ID-shaped access token with it. */
    private class SyntheticJwks : AutoCloseable {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = keys.public as RSAPublicKey
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/certs") { exchange ->
                val n = base64(publicKey.modulus)
                val e = base64(publicKey.publicExponent)
                val body = """{"keys":[{"kty":"RSA","kid":"$KEY_ID","use":"sig","alg":"RS256","n":"$n","e":"$e"}]}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        fun properties(): Map<String, String> = mapOf(
            "id.itmo.jwks-url" to "http://127.0.0.1:${server.address.port}/certs",
            "id.itmo.issuer" to ISSUER,
        )

        fun token(isu: Int): String = JWT.create().withKeyId(KEY_ID).withIssuer(ISSUER).withClaim("isu", isu)
            .sign(Algorithm.RSA256(publicKey, keys.private as RSAPrivateKey))

        override fun close() = server.stop(0)

        private fun base64(value: BigInteger): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())

        private companion object {
            const val KEY_ID = "synthetic-startup-key"
            const val ISSUER = "https://id.example.test/realms/synthetic"
        }
    }

    private class ExternalFakes {
        val api: MyItmoApi = mock(MyItmoApi::class.java)
        val auth: AuthHelper = mock(AuthHelper::class.java)
        val firebaseApp: FirebaseApp = mock(FirebaseApp::class.java)
        val messaging: FirebaseMessaging = mock(FirebaseMessaging::class.java)
        val verifier: ItmoJwtVerifier = mock(ItmoJwtVerifier::class.java)
        val scheduler: TaskScheduler = mock(TaskScheduler::class.java)
        val isu: IsuClient = mock(IsuClient::class.java)
        val gemini: GeminiClient = mock(GeminiClient::class.java)
        val apiRequests = AtomicInteger()
        val scheduleRequests = AtomicInteger()

        @Volatile var available = true
        private val scheduled = mutableListOf<Runnable>()

        init {
            doReturn(CLOCK).`when`(scheduler).clock
            doAnswer { invocation ->
                scheduled.add(invocation.getArgument(0))
                mock(ScheduledFuture::class.java)
            }.`when`(scheduler).schedule(
                any(Runnable::class.java) ?: Runnable { },
                any(Trigger::class.java) ?: CronTrigger("0 * * * * *"),
            )
            doReturn(
                TokenResponse().apply {
                    accessToken = "synthetic-rotated-access"
                    refreshToken = "synthetic-rotated-refresh"
                    idToken = "synthetic-rotated-id"
                    expiresIn = 3600
                    refreshExpiresIn = 86400
                },
            ).`when`(auth).refreshTokens(anyString())
            doAnswer {
                response(
                    listOf(
                        TimeSlot().apply {
                            id = 1
                            timeStart = "12:00"
                            timeEnd = "13:00"
                        },
                    ),
                )
            }.`when`(api).getSportTimeSlots()
            doAnswer {
                response(
                    SportFilters().apply {
                        buildingId = listOf(reference(1, "Synthetic building"))
                        sectionId = listOf(reference(1, "Synthetic section"))
                        teacherIsu = listOf(reference(1, "Synthetic teacher"))
                        sportTypeId = emptyList()
                    },
                )
            }.`when`(api).getSportFilters()
            doAnswer { invocation ->
                scheduleRequests.incrementAndGet()
                assertEquals(LocalDate.now(CLOCK), invocation.getArgument(0))
                assertEquals(LocalDate.now(CLOCK).plusDays(21), invocation.getArgument(1))
                response(
                    listOf(
                        SportSchedule().apply {
                            date = LocalDate.now(CLOCK)
                            lessons = listOf(
                                ApiSportLesson().apply {
                                    id = LESSON_ID
                                    sectionId = 1
                                    sectionName = "Synthetic section"
                                    sectionLevel = 1
                                    lessonLevel = 1
                                    typeId = 1
                                    timeSlotId = 1
                                    buildingId = 1
                                    teacherIsu = 1
                                    roomId = 1
                                    roomName = "Synthetic room"
                                    date = OffsetDateTime.ofInstant(NOW, CLOCK.zone).withHour(12)
                                    dateEnd = date.plusHours(1)
                                    available = 0
                                },
                            )
                        },
                    ),
                )
            }.`when`(api).getSportSchedule(any(), any(), isNull(), isNull(), isNull())
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> response(payload: T): Call<ResultResponse<T>> {
            val call = mock(Call::class.java) as Call<ResultResponse<T>>
            doAnswer {
                apiRequests.incrementAndGet()
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Upstream request inside a transaction")
                if (!available) throw IOException("Synthetic upstream unavailable")
                Response.success(
                    ResultResponse<T>().apply {
                        errorCode = 0
                        result = payload
                    },
                )
            }.`when`(call).execute()
            return call
        }

        fun runScheduled(methodName: String) {
            // Spring 6.2 wraps tasks for outcome tracking; its public description delegates to the scheduled method.
            val description = "${SportUpdateService::class.java.name}.$methodName"
            val matches = scheduled.filter { it.toString() == description }
            assertEquals(1, matches.size, "Missing or duplicate scheduled task: $description; registered: $scheduled")
            matches.single().run()
        }

        fun assertNoExternalDelivery() {
            verifyNoInteractions(firebaseApp, messaging, gemini)
            // Spring legitimately invokes the mock's @PostConstruct init; no JWT verification may contact an issuer.
            verify(verifier, never()).verifyAndDecode(anyString())
            verify(verifier, never()).verifyAccessToken(anyString())
        }

        private fun reference(id: Long, value: String) = IdValuePair().apply {
            this.id = id
            this.value = value
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-08T22:30:00Z")
        private val CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Moscow"))
        private const val BOOTSTRAP = "synthetic-startup-bootstrap"
        private const val OWNER_ISU = 920201
        private const val LESSON_ID = 920201L
    }
}
