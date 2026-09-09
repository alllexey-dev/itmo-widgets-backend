package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.Device
import dev.alllexey.itmowidgets.backend.model.FriendRequestEntity
import dev.alllexey.itmowidgets.backend.model.MyItmoStorage
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.tool.schema.spi.SchemaManagementException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.jdbc.core.JdbcTemplate

class PostgreSqlMigrationTest @Autowired constructor(
    private val flyway: Flyway,
    private val jdbc: JdbcTemplate,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @Test
    fun `Spring starts from Flyway schema with safe settings and all current tables`() {
        // The inherited slice uses production properties: Hibernate must validate, not create tables.
        assertEquals("validate", em.entityManager.entityManagerFactory.properties["hibernate.hbm2ddl.auto"])
        assertEquals("1", flyway.info().current().version.toString())
        assertFalse(flyway.configuration.isBaselineOnMigrate)
        assertTrue(flyway.configuration.isCleanDisabled)
        assertTrue(flyway.configuration.isValidateOnMigrate)
        val tables = jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
            String::class.java,
        ).toSet()
        assertEquals(setOf(
            "devices", "faculties", "friend_requests", "groups", "lessons", "my_itmo_storage",
            "qualifications", "sport_auto_sign_entries", "sport_buildings", "sport_free_sign_entries",
            "sport_lessons", "sport_sections", "sport_teachers", "sport_time_slots", "sport_update_logs",
            "users", "user_settings", "user_sport_lessons", "user_groups", "sport_update_logs_new_lessons",
        ), tables)
        assertEquals("uuid", jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='id'",
            String::class.java,
        ))
        assertEquals("timestamp with time zone", jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='sport_lessons' AND column_name='starts_at'",
            String::class.java,
        ))
    }

    @Test
    fun `UUID text timestamps enums identity and log relationships round trip on PostgreSQL`() {
        val createdAt = Instant.parse("2026-09-08T09:00:00.123456Z")
        val owner = em.persist(User(isu = 910001, pictureUrl = null, name = "Длинное имя " + "я".repeat(600), createdAt = createdAt).apply {
            settings = UserSettingsEntity(user = this)
        })
        val friend = em.persist(User(isu = 910002, pictureUrl = null, name = "Друг", createdAt = createdAt).apply {
            settings = UserSettingsEntity(user = this)
        })
        val request = em.persist(FriendRequestEntity(from = owner, to = friend, createdAt = createdAt, lastActivatedAt = createdAt))
        val device = em.persist(Device(user = owner, fcmToken = "synthetic-not-a-real-fcm-token", deviceName = "Test", lastLogin = createdAt))
        em.persist(MyItmoStorage(1, null, 0, null, 0, null))
        val start = OffsetDateTime.parse("2026-09-08T23:45:00.123456+03:00")
        val lesson = em.persist(SportLesson(
            id = 100,
            section = em.persist(SportSection(1, "Секция")),
            sectionLevel = 1, lessonLevel = 1, typeId = 1, sectionName = "Секция",
            timeSlot = em.persist(SportTimeSlot(1, "23:45", "00:45")),
            building = em.persist(SportBuilding(1, "Здание")),
            teacher = em.persist(SportTeacher(1, "Преподаватель")),
            roomId = 1, roomName = "Аудитория", start = start, end = start.plusHours(1),
        ))
        val queue = em.persist(SportFreeSignEntity(user = owner, lesson = lesson, forceSign = true, status = QueueEntryStatus.NOTIFIED, createdAt = createdAt))
        val log = em.persistAndFlush(SportUpdateLog(updateTimestamp = createdAt, newLessonsAdded = 1, newLessons = mutableListOf(lesson)))
        em.clear()

        assertTrue(assertNotNull(queue.id) > 0)
        assertTrue(log.id > 0)
        val storedOwner = em.find(User::class.java, owner.id)
        assertEquals(owner.name, storedOwner.name)
        assertEquals(createdAt, storedOwner.createdAt)
        assertEquals(owner.id, em.find(Device::class.java, device.id).user.id)
        assertEquals(friend.id, em.find(FriendRequestEntity::class.java, request.id).to.id)
        assertEquals(QueueEntryStatus.NOTIFIED, em.find(SportFreeSignEntity::class.java, queue.id).status)
        assertEquals(start.toInstant(), em.find(SportLesson::class.java, lesson.id).start.toInstant())
        assertEquals(start.plusHours(1).toInstant(), em.find(SportLesson::class.java, lesson.id).end.toInstant())
        assertEquals(listOf(lesson.id), em.find(SportUpdateLog::class.java, log.id).newLessons.map { it.id })
        assertEquals(0, em.find(MyItmoStorage::class.java, 1L).accessTokenExpiresAt)
    }

    @Test
    fun `repeat migration validates history and preserves existing data`() {
        val schema = newSchemaName()
        val migration = isolatedFlyway(schema)
        assertEquals(1, migration.migrate().migrationsExecuted)
        val id = UUID.randomUUID()
        connection().use { connection ->
            connection.prepareStatement("INSERT INTO $schema.users (id, isu, name) VALUES (?, 910001, 'Сохранить')").use {
                it.setObject(1, id)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO $schema.user_settings (user_id) VALUES (?)").use {
                it.setObject(1, id)
                it.executeUpdate()
            }
        }
        assertEquals(0, migration.migrate().migrationsExecuted)
        migration.validate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM $schema.users WHERE isu=910001").use {
                    assertTrue(it.next())
                    assertEquals("Сохранить", it.getString(1))
                    assertFalse(it.next())
                }
                statement.executeQuery("SELECT count(*) FROM $schema.flyway_schema_history WHERE type='SQL' AND success").use {
                    assertTrue(it.next())
                    assertEquals(1, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `nonempty unmanaged schema is refused without baselining or deleting data`() {
        val schema = newSchemaName()
        connection().use { connection ->
            connection.createStatement().use {
                it.execute("CREATE SCHEMA $schema")
                it.execute("CREATE TABLE $schema.existing_data (value integer)")
                it.execute("INSERT INTO $schema.existing_data VALUES (42)")
            }
        }
        assertFailsWith<FlywayException> { isolatedFlyway(schema).migrate() }
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM $schema.existing_data").use {
                    assertTrue(it.next())
                    assertEquals(42, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `migration checksum mismatch and clean command fail closed`() {
        val schema = newSchemaName()
        val migration = isolatedFlyway(schema)
        migration.migrate()
        assertFailsWith<FlywayException> { migration.clean() }
        connection().use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE $schema.flyway_schema_history SET checksum=checksum+1 WHERE version='1'")
            }
        }
        assertFailsWith<FlywayException> { migration.migrate() }
    }

    @Test
    fun `Hibernate rejects schema drift instead of recreating a missing column`() {
        val schema = newSchemaName()
        isolatedFlyway(schema).migrate()
        connection().use { connection ->
            connection.createStatement().use { it.execute("ALTER TABLE $schema.users DROP COLUMN name") }
        }
        val postgres = PostgreSqlTestDatabase.container
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.driver_class" to "org.postgresql.Driver",
            "hibernate.connection.url" to postgres.jdbcUrl,
            "hibernate.connection.username" to postgres.username,
            "hibernate.connection.password" to postgres.password,
            "hibernate.default_schema" to schema,
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.hbm2ddl.auto" to "validate",
        )).build()
        try {
            val metadata = MetadataSources(registry)
            em.entityManager.metamodel.entities.forEach { metadata.addAnnotatedClass(it.javaType) }
            assertFailsWith<SchemaManagementException> { metadata.buildMetadata().buildSessionFactory().close() }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='users' AND column_name='name'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `schema enforces foreign keys privacy enum values and unique identities`() {
        val schema = newSchemaName()
        isolatedFlyway(schema).migrate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                val id = UUID.randomUUID()
                statement.execute("INSERT INTO $schema.users(id, isu) VALUES ('$id', 910001)")
                statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$id')")
                assertEquals("23505", assertFailsWith<SQLException> {
                    statement.execute("INSERT INTO $schema.users(id, isu) VALUES ('${UUID.randomUUID()}', 910001)")
                }.sqlState)
                assertEquals("23514", assertFailsWith<SQLException> {
                    statement.execute("UPDATE $schema.user_settings SET sport_visibility='UNKNOWN' WHERE user_id='$id'")
                }.sqlState)
                assertEquals("23505", assertFailsWith<SQLException> {
                    statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$id')")
                }.sqlState)
                assertEquals("23503", assertFailsWith<SQLException> {
                    statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('${UUID.randomUUID()}')")
                }.sqlState)
                for (column in listOf("schedule_visibility", "sport_visibility")) {
                    assertEquals("23502", assertFailsWith<SQLException> {
                        statement.execute("UPDATE $schema.user_settings SET $column=NULL WHERE user_id='$id'")
                    }.sqlState)
                    assertEquals("23514", assertFailsWith<SQLException> {
                        statement.execute("UPDATE $schema.user_settings SET $column='UNKNOWN' WHERE user_id='$id'")
                    }.sqlState)
                }
                assertEquals("23503", assertFailsWith<SQLException> {
                    statement.execute("INSERT INTO $schema.user_sport_lessons(user_id, lesson_id) VALUES ('$id', 999)")
                }.sqlState)
            }
        }
    }

    @Test
    fun `shared settings identity has only audiences and foreign key deletion never removes another user`() {
        val schema = newSchemaName()
        isolatedFlyway(schema).migrate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                val first = UUID.randomUUID()
                val second = UUID.randomUUID()
                val third = UUID.randomUUID()
                statement.execute("INSERT INTO $schema.users(id, isu) VALUES ('$first', 910001), ('$second', 910002), ('$third', 910003)")
                statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$first'), ('$second'), ('$third')")
                statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='$schema' AND table_name='user_settings'").use {
                    val columns = buildSet { while (it.next()) add(it.getString(1)) }
                    assertEquals(setOf("user_id", "auto_sign_limit", "schedule_visibility", "sport_visibility"), columns)
                }
                statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='users' AND column_name='settings_id'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
                statement.executeQuery("SELECT schedule_visibility, sport_visibility FROM $schema.user_settings WHERE user_id='$first'").use {
                    assertTrue(it.next())
                    assertEquals("FRIENDS", it.getString(1))
                    assertEquals("FRIENDS", it.getString(2))
                }
                statement.execute("DELETE FROM $schema.users WHERE id='$first'")
                statement.executeQuery("SELECT count(*) FROM $schema.user_settings WHERE user_id='$first'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
                statement.execute("DELETE FROM $schema.user_settings WHERE user_id='$second'")
                statement.executeQuery("SELECT id FROM $schema.users").use {
                    val ids = buildSet { while (it.next()) add(it.getObject(1, UUID::class.java)) }
                    assertEquals(setOf(second, third), ids)
                }
                statement.executeQuery("SELECT user_id FROM $schema.user_settings").use {
                    assertTrue(it.next())
                    assertEquals(third, it.getObject(1, UUID::class.java))
                    assertFalse(it.next())
                }
            }
        }
    }

    @Test
    fun `migration runs as a non-superuser schema owner`() {
        val schema = newSchemaName()
        val role = "role_$schema"
        val password = "isolated-test-role-password"
        connection().use { connection ->
            connection.createStatement().use {
                it.execute("CREATE ROLE $role LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                it.execute("CREATE SCHEMA $schema AUTHORIZATION $role")
            }
        }
        val migration = Flyway.configure().configuration(flyway.configuration)
            .dataSource(PostgreSqlTestDatabase.container.jdbcUrl, role, password)
            .schemas(schema).defaultSchema(schema).load()
        assertEquals(1, migration.migrate().migrationsExecuted)
        migration.validate()
    }

    // Random schemas exist only inside this JVM's disposable container, never in an external DB.
    private fun newSchemaName(): String = "migration_" + UUID.randomUUID().toString().replace("-", "")

    private fun isolatedFlyway(schema: String): Flyway = Flyway.configure()
        .configuration(flyway.configuration)
        .schemas(schema).defaultSchema(schema).load()

    private fun connection(): Connection = PostgreSqlTestDatabase.container.let {
        DriverManager.getConnection(it.jdbcUrl, it.username, it.password)
    }
}
