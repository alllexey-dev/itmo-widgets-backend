package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V14__deleted_user_placeholders.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V14DeletedUserPlaceholdersTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("deleted_user_placeholders")

    @Test
    fun `placeholders lose the ISU they were named after and their audit targets follow`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val living = UUID.randomUUID()
        val deleted = UUID.randomUUID()
        // The placeholder of ISU 1 holds -1, the first number the sequence hands out.
        val small = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.users(id, isu, created_at) VALUES ('$living', 964101, $SQL_START), " +
                "('$deleted', -964102, $SQL_START), ('$small', -1, $SQL_END)",
        )
        execute(
            "INSERT INTO $schema.admin_audit(id, actor_id, action, target, created_at) VALUES " +
                "('${UUID.randomUUID()}', '$deleted', 'ACCOUNT_DELETED', 'user:-964102', $SQL_START), " +
                "('${UUID.randomUUID()}', '$living', 'ROLE_GRANTED', 'user:-1', $SQL_START), " +
                "('${UUID.randomUUID()}', '$living', 'ROLE_GRANTED', 'user:964101', $SQL_START)",
        )

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(964101, isuOf(schema, living))
        // In creation order, skipping -1 while the placeholder of ISU 1 still held it.
        assertEquals(-2, isuOf(schema, deleted))
        assertEquals(-3, isuOf(schema, small))
        assertEquals(
            setOf("user:-2", "user:-3", "user:964101"),
            jdbc.queryForList("SELECT target FROM $schema.admin_audit", String::class.java).toSet(),
        )
        assertEquals(4, count("SELECT nextval('$schema.deleted_user_isu_seq')"))
    }

    @Test
    fun `runs as an application role without the TEMP privilege`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        execute("INSERT INTO $schema.users(id, isu, created_at) VALUES ('${UUID.randomUUID()}', -964103, $SQL_START)")
        // Like the deployed database: the application role owns nothing outside its grants and has no TEMP.
        val role = "app_" + schema.takeLast(12)
        val database = PostgreSqlTestDatabase.container.databaseName
        execute("CREATE ROLE $role LOGIN PASSWORD 'synthetic'")
        execute("REVOKE TEMP ON DATABASE $database FROM PUBLIC")
        execute("GRANT USAGE, CREATE ON SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL TABLES IN SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA $schema TO $role")
        try {
            val asApplication = Flyway.configure()
                .configuration(isolatedFlyway(schema, target = version).configuration)
                .dataSource(PostgreSqlTestDatabase.container.jdbcUrl, role, "synthetic")
                .load()

            assertEquals(1, asApplication.migrate().migrationsExecuted)
            assertEquals(1, count("SELECT count(*) FROM $schema.users WHERE isu = -1"))
        } finally {
            execute("GRANT TEMP ON DATABASE $database TO PUBLIC")
        }
    }

    private fun isuOf(schema: String, id: UUID): Long = count("SELECT isu FROM $schema.users WHERE id = '$id'")
}
