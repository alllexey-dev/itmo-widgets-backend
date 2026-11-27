package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V36__drive_file_links.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V36DriveFileLinksTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("drive_file_links")
    private val id = "1SyntheticDriveXlsx_0123456789-abc"
    private val sheet = "https://docs.google.com/spreadsheets/d/$id"

    @Test
    fun `Drive file links move to the sheet of their id and every other link stays`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val owner = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$owner', 963361)")
        val moved = listOf(
            "https://drive.google.com/file/d/$id/view?usp=sharing",
            "https://drive.google.com/file/d/$id",
            "https://drive.google.com/file/u/1/d/$id/edit",
            "https://drive.google.com/open?id=$id",
            "https://drive.google.com/open?authuser=0&id=$id",
        ).associateWith { link(schema, owner, it) }
        val kept = listOf(
            "https://drive.google.com/drive/folders/$id",
            "https://drive.google.com/file/d/short/view",
            "https://drive.google.com/uc?id=$id",
            "https://example.org/file/d/$id",
        ).associateWith { link(schema, owner, it) }

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        for ((url, link) in moved) assertEquals(listOf(sheet, sheet, url), stored(schema, link), url)
        for ((url, link) in kept) assertEquals(listOf(url, url, url), stored(schema, link), url)
    }

    /** The link's normalized URL, its revision's, and its opened URL. */
    private fun stored(schema: String, link: UUID): List<String?> = listOf(
        jdbc.queryForObject("SELECT normalized_url FROM $schema.subject_links WHERE id = ?", String::class.java, link),
        jdbc.queryForObject("SELECT normalized_url FROM $schema.subject_link_revisions WHERE link_id = ?", String::class.java, link),
        jdbc.queryForObject("SELECT url FROM $schema.subject_links WHERE id = ?", String::class.java, link),
    )

    private fun link(schema: String, owner: UUID, url: String): UUID {
        val link = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.subject_links(id, owner_id, subject_id, subject_name, period_key, category, url, normalized_url, " +
                "visibility, flow_id, created_at, updated_at) VALUES ('$link', '$owner', 42, 'Synthetic subject', '2026-1', " +
                "'SCORES', '$url', '$url', 'ALL', NULL, $SQL_START, $SQL_START)",
        )
        execute(
            "INSERT INTO $schema.subject_link_revisions(id, link_id, number, category, url, normalized_url, visibility, flow_id, " +
                "status, submitted_at, decided_at) VALUES ('${UUID.randomUUID()}', '$link', 1, 'SCORES', '$url', '$url', 'ALL', NULL, " +
                "'APPROVED', $SQL_START, $SQL_END)",
        )
        return link
    }
}
