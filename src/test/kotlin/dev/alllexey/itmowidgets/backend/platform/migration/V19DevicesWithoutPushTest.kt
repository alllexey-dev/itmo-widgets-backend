package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V19__devices_without_push.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V19DevicesWithoutPushTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("devices_without_push")

    @Test
    fun `existing devices keep their token and FCM`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$user', 963201)")
        execute(
            "INSERT INTO $schema.devices(id, user_id, fcm_token, device_name, last_login) " +
                "VALUES ('${UUID.randomUUID()}', '$user', 'synthetic-token-963201', 'Synthetic phone', $SQL_START)",
        )

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(
            1,
            count("SELECT count(*) FROM $schema.devices WHERE fcm_token = 'synthetic-token-963201' AND push_provider = 'FCM'"),
        )
    }

    @Test
    fun `a device without a token has the provider NONE, one per user and platform`() {
        withConstraintSchema(target = version) { schema, sql, owner, friend ->
            fun insert(user: UUID, values: Map<String, String>, omit: String = "") = insertSql(
                schema,
                "devices",
                mapOf(
                    "id" to "'${UUID.randomUUID()}'",
                    "user_id" to "'$user'",
                    "fcm_token" to "NULL",
                    "push_provider" to "'NONE'",
                    "device_name" to "'Synthetic phone'",
                    "last_login" to SQL_START,
                    "platform" to "'IOS'",
                ) + values - omit,
            )
            assertEquals(1, sql.executeUpdate(insert(owner, emptyMap())))
            assertSqlState(sql, "23505", insert(owner, emptyMap()))
            assertEquals(1, sql.executeUpdate(insert(owner, mapOf("platform" to "'ANDROID'"))))
            assertEquals(1, sql.executeUpdate(insert(friend, emptyMap())))
            assertEquals(1, sql.executeUpdate(insert(owner, mapOf("fcm_token" to "'synthetic-a'", "push_provider" to "'FCM'"))))
            // A row inserted the way earlier images insert gets FCM with its token.
            assertEquals(1, sql.executeUpdate(insert(owner, mapOf("fcm_token" to "'synthetic-b'"), omit = "push_provider")))

            assertSqlState(sql, "23514", insert(friend, mapOf("push_provider" to "'FCM'")))
            assertSqlState(sql, "23514", insert(friend, mapOf("fcm_token" to "'synthetic-c'")))
            assertSqlState(sql, "23514", insert(friend, mapOf("fcm_token" to "'synthetic-d'", "push_provider" to "'APNS'")))
        }
    }

    @Test
    fun `runs as an application role without the TEMP privilege`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val role = "app_" + schema.takeLast(12)
        val database = PostgreSqlTestDatabase.container.databaseName
        execute("CREATE ROLE $role LOGIN PASSWORD 'synthetic'")
        execute("REVOKE TEMP ON DATABASE $database FROM PUBLIC")
        execute("GRANT USAGE, CREATE ON SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL TABLES IN SCHEMA $schema TO $role")
        // ALTER TABLE and CREATE INDEX need ownership; the deployed application role owns every table it created.
        execute("ALTER TABLE $schema.devices OWNER TO $role")
        try {
            val asApplication = Flyway.configure()
                .configuration(isolatedFlyway(schema, target = version).configuration)
                .dataSource(PostgreSqlTestDatabase.container.jdbcUrl, role, "synthetic")
                .load()

            assertEquals(1, asApplication.migrate().migrationsExecuted)
        } finally {
            execute("GRANT TEMP ON DATABASE $database TO PUBLIC")
        }
    }
}
