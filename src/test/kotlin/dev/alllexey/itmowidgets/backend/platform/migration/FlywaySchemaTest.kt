package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialEntity
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSection
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportTeacher
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.model.UserSportLesson
import dev.alllexey.itmowidgets.backend.feature.sport.web.QueueEntryStatus
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.tool.schema.spi.SchemaManagementException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Flyway settings, the current schema end to end and fail-closed migration behaviour. */
class FlywaySchemaTest : MigrationTestBase() {
    @Test
    fun `Spring starts from the Flyway schema at the latest script with safe settings`() {
        // The inherited slice uses production properties: Hibernate must validate, not create tables.
        assertEquals("validate", em.entityManager.entityManagerFactory.properties["hibernate.hbm2ddl.auto"])
        assertEquals(MigrationScripts.latest, flyway.info().current().version.toString())
        assertFalse(flyway.configuration.isBaselineOnMigrate)
        assertTrue(flyway.configuration.isCleanDisabled)
        assertTrue(flyway.configuration.isValidateOnMigrate)
        assertEquals(
            "uuid",
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='id'",
                String::class.java,
            ),
        )
        assertEquals(
            "timestamp with time zone",
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='sport_lessons' AND column_name='starts_at'",
                String::class.java,
            ),
        )
    }

    @Test
    fun `V1 to V10 leave exactly these tables`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = "10").migrate()
        val tables = jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = ? AND tablename <> 'flyway_schema_history'",
            String::class.java,
            schema,
        ).toSet()
        assertEquals(
            setOf(
                "devices", "faculties", "friendships", "groups", "lessons", "my_itmo_storage",
                "qualifications", "sport_auto_sign_entries", "sport_buildings", "sport_free_sign_entries",
                "sport_lessons", "sport_sections", "sport_teachers", "sport_time_slots", "sport_update_logs",
                "users", "user_settings", "user_sport_lessons", "user_groups", "sport_update_logs_new_lessons",
                "user_roles", "moderation_cases", "moderation_decisions", "user_restrictions", "moderation_settings",
                "moderation_reports", "subject_links", "subject_link_revisions",
                "subject_link_votes", "subject_link_pins", "user_subject_flows",
                "web_login_challenges", "web_sessions", "app_settings", "admin_audit",
                "external_teacher_reviews", "external_review_sync_state", "service_credentials",
                "teacher_reviews", "teacher_review_revisions", "teacher_review_votes", "external_teacher_review_votes",
                "teacher_review_flows", "isu_potoks", "isu_potok_teachers", "isu_potok_members",
                "teacher_summaries", "teacher_summary_state",
            ),
            tables,
        )
    }

    @Test
    fun `UUID text timestamps enums identity and log relationships round trip on PostgreSQL`() {
        val createdAt = Instant.parse("2026-09-08T09:00:00.123456Z")
        val owner = em.persist(
            User(isu = 910001, pictureUrl = null, name = "Длинное имя " + "я".repeat(600), createdAt = createdAt).apply {
                settings = UserSettingsEntity(user = this)
            },
        )
        val friend = em.persist(
            User(isu = 910002, pictureUrl = null, name = "Друг", createdAt = createdAt).apply {
                settings = UserSettingsEntity(user = this)
            },
        )
        val request = em.persist(FriendshipEntity(requester = owner, addressee = friend, createdAt = createdAt))
        val device = em.persist(
            Device(user = owner, fcmToken = "synthetic-not-a-real-fcm-token", deviceName = "Test", lastLogin = createdAt),
        )
        val start = OffsetDateTime.parse("2026-09-08T23:45:00.123456+03:00")
        val lesson = em.persist(
            SportLesson(
                id = 100,
                section = em.persist(SportSection(1, "Секция")),
                sectionLevel = 1, lessonLevel = 1, typeId = 1, sectionName = "Секция",
                timeSlot = em.persist(SportTimeSlot(1, "23:45", "00:45")),
                buildingId = 1L,
                teacher = em.persist(SportTeacher(1, "Преподаватель")),
                roomId = 1, roomName = "Аудитория", start = start, end = start.plusHours(1), lastSeenAt = createdAt,
            ),
        )
        val queue = em.persist(
            SportFreeSignEntity(user = owner, lesson = lesson, forceSign = true, status = QueueEntryStatus.NOTIFIED, createdAt = createdAt),
        )
        val log = em.persistAndFlush(
            SportUpdateLog(
                updateTimestamp = createdAt,
                newLessonsAdded = 1,
                newLessons = mutableListOf(lesson),
                outcome = SportUpdateOutcome.SUCCESS,
                durationMillis = 0,
                receivedLessons = 1,
                updatedLessons = 0,
                skippedLessons = 0,
            ),
        )
        em.clear()

        assertTrue(assertNotNull(queue.id) > 0)
        assertTrue(log.id > 0)
        val storedOwner = em.find(User::class.java, owner.id)!!
        assertEquals(owner.name, storedOwner.name)
        assertEquals(createdAt, storedOwner.createdAt)
        assertEquals(owner.id, em.find(Device::class.java, device.id)!!.user.id)
        assertEquals(friend.id, em.find(FriendshipEntity::class.java, request.id)!!.addressee.id)
        assertEquals(QueueEntryStatus.NOTIFIED, em.find(SportFreeSignEntity::class.java, queue.id!!)!!.status)
        assertEquals(start.toInstant(), em.find(SportLesson::class.java, lesson.id)!!.start.toInstant())
        assertEquals(start.plusHours(1).toInstant(), em.find(SportLesson::class.java, lesson.id)!!.end.toInstant())
        assertEquals(listOf(lesson.id), em.find(SportUpdateLog::class.java, log.id)!!.newLessons.map { it.id })
        assertEquals(ServiceCredentialStatus.MISSING, em.find(ServiceCredentialEntity::class.java, "MY_ITMO_ACCESS_TOKEN")!!.status)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `deleting a log removes only its links and preserves catalog queues and bookings`(viaSql: Boolean) {
        val now = Instant.parse("2026-09-08T09:00:00Z")
        val user = em.persist(
            User(isu = 910003, pictureUrl = null, name = "Synthetic owner", createdAt = now).apply {
                settings = UserSettingsEntity(user = this)
            },
        )
        val start = OffsetDateTime.parse("2026-09-22T12:00:00+03:00")
        val lesson = em.persist(
            SportLesson(
                id = 103,
                section = em.persist(SportSection(103, "Synthetic section")),
                sectionLevel = 1, lessonLevel = 1, typeId = 1, sectionName = "Synthetic section",
                timeSlot = em.persist(SportTimeSlot(103, "12:00", "13:00")),
                buildingId = 103L,
                teacher = em.persist(SportTeacher(103, "Synthetic teacher")),
                roomId = 1, roomName = "Synthetic room", start = start, end = start.plusHours(1), lastSeenAt = now,
            ),
        )
        val auto = em.persist(SportAutoSignEntity(user = user, prototypeLesson = lesson, realLesson = null, createdAt = now))
        val free = em.persist(SportFreeSignEntity(user = user, lesson = lesson, forceSign = true, createdAt = now))
        val booking = em.persist(UserSportLesson(user = user, lesson = lesson, createdAt = now))
        val log = em.persistAndFlush(
            SportUpdateLog(
                updateTimestamp = now,
                newLessonsAdded = 1,
                newLessons = mutableListOf(lesson),
                outcome = SportUpdateOutcome.SUCCESS,
                durationMillis = 0,
                receivedLessons = 1,
                updatedLessons = 0,
                skippedLessons = 0,
            ),
        )
        em.clear()

        if (viaSql) {
            jdbc.update("DELETE FROM sport_update_logs WHERE id=?", log.id)
        } else {
            em.remove(em.find(SportUpdateLog::class.java, log.id)!!)
            em.flush()
        }
        em.clear()

        assertEquals(
            0L,
            jdbc.queryForObject("SELECT count(*) FROM sport_update_logs_new_lessons WHERE sport_update_log_id=?", Long::class.java, log.id),
        )
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs WHERE id=?", Long::class.java, log.id))
        assertNotNull(em.find(SportLesson::class.java, lesson.id))
        assertEquals(lesson.id, em.find(SportAutoSignEntity::class.java, auto.id!!)!!.prototypeLesson.id)
        assertEquals(lesson.id, em.find(SportFreeSignEntity::class.java, free.id!!)!!.lesson.id)
        assertEquals(lesson.id, em.find(UserSportLesson::class.java, booking.id)!!.lesson.id)
        assertNotNull(em.find(UserSettingsEntity::class.java, user.id))
    }

    @Test
    fun `repeat migration validates history and preserves existing data`() {
        val schema = newSchemaName()
        val migration = isolatedFlyway(schema)
        assertEquals(MigrationScripts.count, migration.migrate().migrationsExecuted)
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
                    assertEquals(MigrationScripts.count, it.getInt(1))
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
        val registry = StandardServiceRegistryBuilder().applySettings(
            mapOf(
                "hibernate.connection.driver_class" to "org.postgresql.Driver",
                "hibernate.connection.url" to postgres.jdbcUrl,
                "hibernate.connection.username" to postgres.username,
                "hibernate.connection.password" to postgres.password,
                "hibernate.default_schema" to schema,
                "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.hbm2ddl.auto" to "validate",
            ),
        ).build()
        try {
            val metadata = MetadataSources(registry)
            em.entityManager.metamodel.entities.forEach { metadata.addAnnotatedClass(it.javaType) }
            assertFailsWith<SchemaManagementException> { metadata.buildMetadata().buildSessionFactory().close() }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='users' AND column_name='name'",
                ).use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
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
        assertEquals(MigrationScripts.count, migration.migrate().migrationsExecuted)
        migration.validate()
    }
}
