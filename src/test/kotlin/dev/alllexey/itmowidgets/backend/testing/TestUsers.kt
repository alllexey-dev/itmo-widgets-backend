package dev.alllexey.itmowidgets.backend.testing

import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Synthetic users for every test: one place that knows how a `users` row and its `user_settings` row are built,
 * instead of a `user()` factory per test class. A schema change to either table edits this file only.
 */
object TestUsers {
    const val NAME = "Synthetic user"

    /** A transient user with its settings row. Visibilities default to the entity's defaults. */
    fun user(
        isu: Int,
        name: String? = NAME,
        createdAt: Instant = TestClock.now(),
        scheduleVisibility: SharingVisibility = SharingVisibility.FRIENDS,
        sportVisibility: SharingVisibility = SharingVisibility.FRIENDS,
        groups: Collection<GroupEntity> = emptyList(),
    ): User = User(isu = isu, pictureUrl = null, name = name, createdAt = createdAt).apply {
        settings = UserSettingsEntity(user = this, scheduleVisibility = scheduleVisibility, sportVisibility = sportVisibility)
        this.groups.addAll(groups)
    }
}

/** Persists and flushes [TestUsers.user], so native SQL and JDBC in the same transaction see the row. */
fun TestEntityManager.persistUser(
    isu: Int,
    name: String? = TestUsers.NAME,
    createdAt: Instant = TestClock.now(),
    groups: Collection<GroupEntity> = emptyList(),
): User = persistAndFlush(TestUsers.user(isu = isu, name = name, createdAt = createdAt, groups = groups))

/** Inserts a user and its default settings with plain SQL, for tests that commit outside JPA; returns the new id. */
fun JdbcTemplate.insertUser(isu: Int, name: String? = TestUsers.NAME, createdAt: Instant? = null): UUID {
    val id = UUID.randomUUID()
    if (createdAt == null) {
        update("INSERT INTO users (id, isu, name) VALUES (?, ?, ?)", id, isu, name)
    } else {
        update("INSERT INTO users (id, isu, name, created_at) VALUES (?, ?, ?, ?)", id, isu, name, Timestamp.from(createdAt))
    }
    update("INSERT INTO user_settings (user_id) VALUES (?)", id)
    return id
}

/** Persisted users with consecutive ISUs from [firstIsu], all created at [createdAt], for tests that need many people. */
class UserSequence(private val em: TestEntityManager, firstIsu: Int, private val createdAt: Instant = TestClock.now()) {
    private var nextIsu = firstIsu

    fun next(name: String? = TestUsers.NAME): User = em.persistUser(nextIsu++, name, createdAt)
}
