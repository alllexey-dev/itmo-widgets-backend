package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test

class UserPrivacySchemaCompatibilityTest {
    @Test
    fun `hibernate update adds nullable varchar columns and preserves legacy rows`() {
        val url = "jdbc:h2:mem:privacy_upgrade_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        val id = UUID.randomUUID()
        schema(url, "create", LegacySettings::class.java)
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("INSERT INTO user_settings (id, auto_sign_limit, sport_sharing, schedule_sharing) VALUES (?, 7, false, true)").use {
                it.setObject(1, id)
                assertEquals(1, it.executeUpdate())
            }
        }
        // This is Hibernate's actual schema-update path, against an isolated old-schema H2 database.
        schema(url, "update", UserSettingsEntity::class.java)
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM user_settings").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(7, rows.getInt("AUTO_SIGN_LIMIT"))
                    assertFalse(rows.getBoolean("SPORT_SHARING"))
                    assertTrue(rows.getBoolean("SCHEDULE_SHARING"))
                    assertEquals(null, rows.getString("SPORT_VISIBILITY"))
                    assertEquals(null, rows.getString("SCHEDULE_VISIBILITY"))
                    assertFalse(rows.next())
                }
                statement.executeQuery("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'USER_SETTINGS' AND COLUMN_NAME IN ('SPORT_VISIBILITY', 'SCHEDULE_VISIBILITY')").use { columns ->
                    var count = 0
                    while (columns.next()) {
                        count++
                        assertEquals("CHARACTER VARYING", columns.getString("DATA_TYPE"))
                        assertEquals("YES", columns.getString("IS_NULLABLE"))
                        assertEquals(16, columns.getInt("CHARACTER_MAXIMUM_LENGTH"))
                    }
                    assertEquals(2, count)
                }
            }
        }
    }

    private fun schema(url: String, action: String, entity: Class<*>) {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.driver_class" to "org.h2.Driver",
            "hibernate.connection.url" to url,
            "hibernate.dialect" to "org.hibernate.dialect.H2Dialect",
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.hbm2ddl.auto" to action,
            "hibernate.show_sql" to "true"
        )).build()
        try {
            MetadataSources(registry).addAnnotatedClass(entity).buildMetadata().buildSessionFactory().use { factory ->
                if (action == "update") factory.openSession().use { session ->
                    val settings = session.createQuery("from UserSettingsEntity", UserSettingsEntity::class.java).singleResult
                    assertEquals(SharingVisibility.NOBODY, settings.effectiveSportVisibility())
                    assertEquals(SharingVisibility.FRIENDS, settings.effectiveScheduleVisibility())
                }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
    }

    @Entity(name = "LegacyPrivacySettings")
    @Table(name = "user_settings")
    class LegacySettings {
        @Id var id: UUID? = null
        @Column(name = "auto_sign_limit") var autoSignLimit: Int = 3
        @Column(name = "sport_sharing") var sportSharing: Boolean = false
        @Column(name = "schedule_sharing") var scheduleSharing: Boolean = false
    }
}
