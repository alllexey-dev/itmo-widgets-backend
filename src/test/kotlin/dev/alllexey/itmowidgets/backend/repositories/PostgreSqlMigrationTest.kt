package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.Device
import dev.alllexey.itmowidgets.backend.model.FriendRequestEntity
import dev.alllexey.itmowidgets.backend.model.MyItmoStorage
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.UserSportLesson
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
            buildingId = 1L,
            teacher = em.persist(SportTeacher(1, "Преподаватель")),
            roomId = 1, roomName = "Аудитория", start = start, end = start.plusHours(1), lastSeenAt = createdAt,
        ))
        val queue = em.persist(SportFreeSignEntity(user = owner, lesson = lesson, forceSign = true, status = QueueEntryStatus.NOTIFIED, createdAt = createdAt))
        val log = em.persistAndFlush(SportUpdateLog(updateTimestamp = createdAt, newLessonsAdded = 1, newLessons = mutableListOf(lesson), outcome = SportUpdateOutcome.SUCCESS, durationMillis = 0, receivedLessons = 1, updatedLessons = 0, skippedLessons = 0))
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `deleting a log removes only its links and preserves catalog queues and bookings`(viaSql: Boolean) {
        val now = Instant.parse("2026-09-08T09:00:00Z")
        val user = em.persist(User(isu = 910003, pictureUrl = null, name = "Synthetic owner", createdAt = now).apply {
            settings = UserSettingsEntity(user = this)
        })
        val start = OffsetDateTime.parse("2026-09-22T12:00:00+03:00")
        val lesson = em.persist(SportLesson(
            id = 103,
            section = em.persist(SportSection(103, "Synthetic section")),
            sectionLevel = 1, lessonLevel = 1, typeId = 1, sectionName = "Synthetic section",
            timeSlot = em.persist(SportTimeSlot(103, "12:00", "13:00")),
            buildingId = 103L,
            teacher = em.persist(SportTeacher(103, "Synthetic teacher")),
            roomId = 1, roomName = "Synthetic room", start = start, end = start.plusHours(1), lastSeenAt = now,
        ))
        val auto = em.persist(SportAutoSignEntity(user = user, prototypeLesson = lesson, realLesson = null, createdAt = now))
        val free = em.persist(SportFreeSignEntity(user = user, lesson = lesson, forceSign = true, createdAt = now))
        val booking = em.persist(UserSportLesson(user = user, lesson = lesson, createdAt = now))
        val log = em.persistAndFlush(SportUpdateLog(updateTimestamp = now, newLessonsAdded = 1, newLessons = mutableListOf(lesson), outcome = SportUpdateOutcome.SUCCESS, durationMillis = 0, receivedLessons = 1, updatedLessons = 0, skippedLessons = 0))
        em.clear()

        if (viaSql) jdbc.update("DELETE FROM sport_update_logs WHERE id=?", log.id)
        else { em.remove(em.find(SportUpdateLog::class.java, log.id)); em.flush() }
        em.clear()

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs_new_lessons WHERE sport_update_log_id=?", Long::class.java, log.id))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs WHERE id=?", Long::class.java, log.id))
        assertNotNull(em.find(SportLesson::class.java, lesson.id))
        assertEquals(lesson.id, em.find(SportAutoSignEntity::class.java, auto.id).prototypeLesson.id)
        assertEquals(lesson.id, em.find(SportFreeSignEntity::class.java, free.id).lesson.id)
        assertEquals(lesson.id, em.find(UserSportLesson::class.java, booking.id).lesson.id)
        assertNotNull(em.find(UserSettingsEntity::class.java, user.id))
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
    fun `database rejects self friendship negative quota and non singleton token storage`() {
        withConstraintSchema { schema, statement, owner, friend ->
            val request = mapOf(
                "id" to "'${UUID.randomUUID()}'", "from_user_id" to "'$owner'", "to_user_id" to "'$friend'",
                "status" to "'ACTIVE'", "created_at" to SQL_START, "last_activated_at" to SQL_START,
            )
            assertSqlState(statement, "23514", insertSql(schema, "friend_requests", request + ("to_user_id" to "'$owner'")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "friend_requests", request)))

            val settings = mapOf("user_id" to "'$owner'", "auto_sign_limit" to "0")
            assertSqlState(statement, "23514", insertSql(schema, "user_settings", settings + ("auto_sign_limit" to "-1")))
            assertSqlState(statement, "23502", insertSql(schema, "user_settings", settings + ("auto_sign_limit" to "NULL")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "user_settings", settings)))

            val storage = mapOf("id" to "1", "refresh_token_expires_at" to "0", "access_token_expires_at" to "0")
            for (invalidId in listOf("-1", "0", "2")) {
                assertSqlState(statement, "23514", insertSql(schema, "my_itmo_storage", storage + ("id" to invalidId)))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "my_itmo_storage", storage)))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both queues reject negative attempts nonpositive limits and null counters`(automatic: Boolean) {
        withConstraintSchema { schema, statement, owner, _ ->
            val table = if (automatic) "sport_auto_sign_entries" else "sport_free_sign_entries"
            val fields = if (automatic) autoFields(owner) else freeFields(owner)
            assertSqlState(statement, "23514", insertSql(schema, table, fields + ("notification_attempts" to "-1")))
            for (invalidMaximum in listOf("-1", "0")) {
                assertSqlState(statement, "23514", insertSql(schema, table, fields + ("max_notification_attempts" to invalidMaximum)))
            }
            for (column in listOf("notification_attempts", "max_notification_attempts")) {
                assertSqlState(statement, "23502", insertSql(schema, table, fields + (column to "NULL")))
            }
            // Zero attempts and one allowed attempt are legitimate lower boundaries.
            assertEquals(1, statement.executeUpdate(insertSql(schema, table, fields)))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `lesson and frozen prediction require end strictly after start`(prediction: Boolean) {
        withConstraintSchema { schema, statement, owner, _ ->
            val table = if (prediction) "sport_auto_sign_entries" else "sport_lessons"
            val fields = if (prediction) autoFields(owner) else lessonFields(id = 2)
            val endColumn = if (prediction) "target_ends_at" else "ends_at"
            for (invalidEnd in listOf(SQL_START, "TIMESTAMPTZ '2026-09-08T08:59:59Z'")) {
                assertSqlState(statement, "23514", insertSql(schema, table, fields + (endColumn to invalidEnd)))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, table, fields)))
        }
    }

    @Test
    fun `required frozen prediction columns reject null even on cancelled entries`() {
        withConstraintSchema { schema, statement, owner, _ ->
            val fields = autoFields(owner)
            val snapshotColumns = listOf(
                "target_section_id", "target_section_name", "target_section_level", "target_lesson_level",
                "target_type_id", "target_time_slot_id", "target_teacher_isu",
                "target_teacher_name", "target_room_id", "target_room_name", "target_starts_at", "target_ends_at",
            )
            for (column in snapshotColumns) {
                assertSqlState(statement, "23502", insertSql(schema, "sport_auto_sign_entries", fields + (column to "NULL")))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_auto_sign_entries", fields)))
        }
    }

    @Test
    fun `raw venue ids do not require filter rows and online snapshots preserve null`() {
        withConstraintSchema { schema, statement, owner, _ ->
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_lessons", lessonFields(id = 2) + ("building_id" to "999999"))))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_lessons", lessonFields(id = 3) + mapOf("building_id" to "NULL", "room_id" to "-1"))))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_auto_sign_entries", autoFields(owner) + mapOf("target_building_id" to "NULL", "target_room_id" to "-1"))))
        }
    }

    @Test
    fun `catalog last seen timestamp is mandatory and accepts deterministic historical values`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = lessonFields(id = 2)
            assertSqlState(statement, "23502", insertSql(schema, "sport_lessons", fields + ("last_seen_at" to "NULL")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_lessons", fields)))
        }
    }

    @Test
    fun `update journal rejects every negative counter and duration while allowing zeros`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = logFields()
            for (column in listOf("duration_millis", "received_lessons", "new_lessons_added", "updated_lessons", "skipped_lessons")) {
                assertSqlState(statement, "23514", insertSql(schema, "sport_update_logs", fields + (column to "-1")))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields)))
        }
    }

    @Test
    fun `update journal requires timestamp outcome duration and every counter`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = logFields()
            for (column in listOf(
                "update_timestamp", "outcome", "duration_millis", "received_lessons",
                "new_lessons_added", "updated_lessons", "skipped_lessons",
            )) {
                assertSqlState(statement, "23502", insertSql(schema, "sport_update_logs", fields + (column to "NULL")))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields)))
        }
    }

    @Test
    fun `update journal constrains outcome and error categories without requiring an error`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = logFields()
            for (column in listOf("outcome", "error_category")) {
                assertSqlState(statement, "23514", insertSql(schema, "sport_update_logs", fields + (column to "'UNKNOWN'")))
            }
            for (outcome in listOf("SUCCESS", "PARTIAL", "FAILED")) {
                assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields + ("outcome" to "'$outcome'"))))
            }
            for (category in listOf("AUTH", "NETWORK", "HTTP", "MAPPING", "PERSISTENCE", "INTERNAL")) {
                assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields + mapOf(
                    "outcome" to "'FAILED'", "error_category" to "'$category'",
                ))))
            }
            statement.executeQuery("SELECT count(*) FROM $schema.sport_update_logs WHERE error_category IS NULL").use {
                assertTrue(it.next())
                assertEquals(3, it.getInt(1))
            }
        }
    }

    @Test
    fun `data invariant checks have stable explicit names in the initial schema`() {
        val expected = setOf(
            "ck_settings_auto_sign_limit", "ck_friend_requests_not_self", "ck_my_itmo_storage_singleton",
            "ck_sport_lessons_time_range", "ck_auto_sign_attempts", "ck_auto_sign_max_attempts",
            "ck_auto_sign_target_time_range", "ck_free_sign_attempts", "ck_free_sign_max_attempts",
            "ck_sport_update_duration", "ck_sport_update_received", "ck_sport_update_new",
            "ck_sport_update_updated", "ck_sport_update_skipped", "ck_sport_update_outcome",
            "ck_sport_update_error_category",
        )
        val names = jdbc.queryForList("""
            SELECT conname FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'c'
        """.trimIndent(), String::class.java).toSet()
        assertEquals(expected, names.intersect(expected))
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

    private fun withConstraintSchema(action: (String, Statement, UUID, UUID) -> Unit) {
        val schema = newSchemaName()
        isolatedFlyway(schema).migrate()
        connection().use { connection ->
            // Auto-commit keeps one deliberately rejected INSERT from poisoning later assertions.
            assertTrue(connection.autoCommit)
            connection.createStatement().use { statement ->
                val owner = UUID.randomUUID()
                val friend = UUID.randomUUID()
                statement.executeUpdate("INSERT INTO $schema.users(id, isu) VALUES ('$owner', 920001), ('$friend', 920002)")
                statement.executeUpdate("INSERT INTO $schema.sport_sections(id, name) VALUES (1, 'Synthetic section')")
                statement.executeUpdate("INSERT INTO $schema.sport_buildings(id, name) VALUES (1, 'Synthetic building')")
                statement.executeUpdate("INSERT INTO $schema.sport_teachers(isu, name) VALUES (1, 'Synthetic teacher')")
                statement.executeUpdate("INSERT INTO $schema.sport_time_slots(id, time_start, time_end) VALUES (1, '12:00', '13:00')")
                statement.executeUpdate(insertSql(schema, "sport_lessons", lessonFields(id = 1)))
                action(schema, statement, owner, friend)
            }
        }
    }

    private fun lessonFields(id: Long): Map<String, String> = mapOf(
        "id" to id.toString(), "section_id" to "1", "section_level" to "1", "lesson_level" to "1",
        "type_id" to "1", "section_name" to "'Synthetic section'", "time_slot_id" to "1",
        "building_id" to "1", "teacher_isu" to "1", "room_id" to "1", "room_name" to "'Synthetic room'",
        "starts_at" to SQL_START, "ends_at" to SQL_END, "last_seen_at" to SQL_START,
    )

    private fun autoFields(owner: UUID): Map<String, String> = mapOf(
        "user_id" to "'$owner'", "prototype_lesson_id" to "1", "target_section_id" to "1",
        "target_section_name" to "'Synthetic section'", "target_section_level" to "1", "target_lesson_level" to "1",
        "target_type_id" to "1", "target_time_slot_id" to "1", "target_building_id" to "1",
        "target_teacher_isu" to "1", "target_teacher_name" to "'Synthetic teacher'",
        "target_room_id" to "1", "target_room_name" to "'Synthetic room'",
        "target_starts_at" to SQL_START, "target_ends_at" to SQL_END,
        "is_cancelled" to "true", "notification_attempts" to "0", "max_notification_attempts" to "1",
    )

    private fun freeFields(owner: UUID): Map<String, String> = mapOf(
        "user_id" to "'$owner'", "lesson_id" to "1", "force_sign" to "false",
        "is_cancelled" to "true", "notification_attempts" to "0", "max_notification_attempts" to "1",
    )

    private fun logFields(): Map<String, String> = mapOf(
        "update_timestamp" to SQL_START, "outcome" to "'SUCCESS'", "duration_millis" to "0",
        "received_lessons" to "0", "new_lessons_added" to "0", "updated_lessons" to "0",
        "skipped_lessons" to "0", "error_category" to "NULL",
    )

    // Only literal synthetic test values and fixed identifiers enter these SQL expressions.
    private fun insertSql(schema: String, table: String, fields: Map<String, String>): String =
        "INSERT INTO $schema.$table (${fields.keys.joinToString()}) VALUES (${fields.values.joinToString()})"

    private fun assertSqlState(statement: Statement, expected: String, sql: String) {
        assertEquals(expected, assertFailsWith<SQLException> { statement.executeUpdate(sql) }.sqlState, sql)
    }

    // Random schemas exist only inside this JVM's disposable container, never in an external DB.
    private fun newSchemaName(): String = "migration_" + UUID.randomUUID().toString().replace("-", "")

    private fun isolatedFlyway(schema: String): Flyway = Flyway.configure()
        .configuration(flyway.configuration)
        .schemas(schema).defaultSchema(schema).load()

    private fun connection(): Connection = PostgreSqlTestDatabase.container.let {
        DriverManager.getConnection(it.jdbcUrl, it.username, it.password)
    }

    companion object {
        private const val SQL_START = "TIMESTAMPTZ '2026-09-08T09:00:00Z'"
        private const val SQL_END = "TIMESTAMPTZ '2026-09-08T10:00:00Z'"
    }
}
