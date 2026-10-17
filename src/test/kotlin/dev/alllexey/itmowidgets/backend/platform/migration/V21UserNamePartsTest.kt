package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V21__user_name_parts.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V21UserNamePartsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("user_name_parts")

    @Test
    fun `adds empty name parts to existing users without rewriting the table`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$user', 964001, 'Synthetic user')")
        val storage = count("SELECT pg_relation_filenode('$schema.users')::bigint")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        // Nullable columns without defaults are a catalog change: the table keeps its file, nothing is rewritten.
        assertEquals(storage, count("SELECT pg_relation_filenode('$schema.users')::bigint"))
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.users WHERE id = '$user' AND name = 'Synthetic user' " +
                    "AND family_name IS NULL AND given_name IS NULL",
            ),
        )
        execute("UPDATE $schema.users SET family_name = 'Synthetic', given_name = 'User' WHERE id = '$user'")
        assertEquals(1, count("SELECT count(*) FROM $schema.users WHERE family_name = 'Synthetic' AND given_name = 'User'"))
    }
}
