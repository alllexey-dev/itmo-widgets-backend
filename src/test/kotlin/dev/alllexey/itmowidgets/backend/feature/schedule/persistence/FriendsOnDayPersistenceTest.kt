package dev.alllexey.itmowidgets.backend.feature.schedule.persistence

import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonContextService
import dev.alllexey.itmowidgets.backend.feature.schedule.web.FriendDay
import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.feature.social.persistence.FriendshipRepository
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.QualificationEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/schedule/friends` on PostgreSQL: the friend, audience and day filters of the real queries, friend order,
 * and a statement count that does not grow with the number of friends.
 */
class FriendsOnDayPersistenceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val lessons: LessonRepository,
    private val users: UserRepository,
    private val friendships: FriendshipRepository,
    entityManagerFactory: EntityManagerFactory,
) : PostgreSqlRepositoryTest() {
    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val clock = Clock.fixed(Instant.parse("2026-09-21T22:30:00Z"), ZoneOffset.UTC)
    private val friendService = FriendService(friendships, users, mock(UserService::class.java), clock)
    private val service = LessonContextService(lessons, users, friendService, UserPrivacyService(friendService), clock)

    private lateinit var faculty: FacultyEntity
    private lateinit var qualification: QualificationEntity

    @BeforeEach
    fun fixture() {
        faculty = em.persist(FacultyEntity(962_001, "Synthetic faculty", "СИН"))
        qualification = em.persist(QualificationEntity(962_001, "Synthetic"))
    }

    @AfterEach
    fun stopCounting() {
        statistics.isStatisticsEnabled = false
    }

    @Test
    fun `friends with a lesson that day whose audience admits the viewer come in friend order with their lessons by start`() {
        val viewer = person(962100, SharingVisibility.NOBODY)
        val older = person(962201, SharingVisibility.ALL)
        val newer = person(962202, SharingVisibility.FRIENDS)
        val hidden = person(962203, SharingVisibility.NOBODY)
        val free = person(962204, SharingVisibility.ALL)
        val pending = person(962205, SharingVisibility.ALL)
        val stranger = person(962206, SharingVisibility.ALL)
        befriend(viewer, older, Instant.parse("2026-09-01T10:00:00Z"))
        befriend(newer, viewer, Instant.parse("2026-09-10T10:00:00Z"))
        befriend(viewer, hidden, Instant.parse("2026-09-02T10:00:00Z"))
        befriend(viewer, free, Instant.parse("2026-09-03T10:00:00Z"))
        em.persist(FriendshipEntity(requester = viewer, addressee = pending, createdAt = Instant.parse("2026-09-04T10:00:00Z")))
        for (owner in listOf(viewer, older, newer, hidden, pending, stranger)) lesson(owner, 1, DAY, "10:00")
        lesson(older, 2, DAY, "08:20")
        lesson(older, 3, DAY.plusDays(1), "08:20")
        lesson(free, 4, DAY.minusDays(1), "08:20")
        em.flush()
        em.clear()

        val result = service.friendsOnDay(viewer, DAY)

        assertEquals(listOf(newer.isu, older.isu), result.map { it.user.isu })
        assertEquals(listOf(1L), result[0].lessons.map { it.pairId })
        assertEquals(listOf(2L, 1L), result[1].lessons.map { it.pairId })
        assertTrue(result.all { it.user.capabilities.canViewSchedule })
        assertEquals(listOf("P3101", "P3201"), result[1].user.groups.map { it.name })
    }

    @Test
    fun `no date means today in Moscow, not in UTC`() {
        val viewer = person(962300, SharingVisibility.NOBODY)
        val friend = person(962301, SharingVisibility.FRIENDS)
        befriend(viewer, friend, Instant.parse("2026-09-01T10:00:00Z"))
        // The clock reads 22:30 UTC on the 21st, already the 22nd in Moscow.
        lesson(friend, 1, LocalDate.parse("2026-09-21"), "10:00")
        lesson(friend, 2, LocalDate.parse("2026-09-22"), "10:00")
        em.flush()
        em.clear()

        assertEquals(listOf(2L), service.friendsOnDay(viewer, null).single().lessons.map { it.pairId })
    }

    @Test
    fun `the number of statements does not grow with the number of friends`() {
        val few = person(962400, SharingVisibility.NOBODY)
        val many = person(962401, SharingVisibility.NOBODY)
        for (index in 0 until 8) {
            val friend = person(962410 + index, if (index % 2 == 0) SharingVisibility.ALL else SharingVisibility.FRIENDS)
            befriend(if (index == 0) few else many, friend, Instant.parse("2026-09-01T10:00:00Z").plusSeconds(index.toLong()))
            if (index == 0) befriend(many, friend, Instant.parse("2026-09-01T09:00:00Z"))
            lesson(friend, 10L + index, DAY, "10:00")
            lesson(friend, 20L + index, DAY, "12:00")
        }
        em.flush()

        val oneFriend = statementsOf { service.friendsOnDay(few, DAY) }
        val eightFriends = statementsOf { service.friendsOnDay(many, DAY) }

        assertEquals(1, oneFriend.first.size)
        assertEquals(8, eightFriends.first.size)
        assertEquals(3L, oneFriend.second, "friend list, friends with settings and groups, lessons")
        assertEquals(oneFriend.second, eightFriends.second)
    }

    private fun statementsOf(call: () -> List<FriendDay>): Pair<List<FriendDay>, Long> {
        em.clear()
        statistics.clear()
        statistics.isStatisticsEnabled = true
        val result = call()
        statistics.isStatisticsEnabled = false
        return result to statistics.prepareStatementCount
    }

    private fun person(isu: Int, schedule: SharingVisibility): User =
        em.persist(TestUsers.user(isu, scheduleVisibility = schedule, groups = listOf(group("P3101"), group("P3201"))))

    private fun group(name: String): GroupEntity = em.find(GroupEntity::class.java, UUID.nameUUIDFromBytes(name.toByteArray()))
        ?: em.persist(
            GroupEntity(
                id = UUID.nameUUIDFromBytes(name.toByteArray()),
                name = name,
                course = if (name == "P3101") 2 else 1,
                qualification = qualification,
                faculty = faculty,
            ),
        )

    private fun befriend(requester: User, addressee: User, respondedAt: Instant) {
        em.persist(
            FriendshipEntity(
                requester = requester,
                addressee = addressee,
                status = FriendshipEntity.Status.ACCEPTED,
                createdAt = respondedAt.minusSeconds(60),
                respondedAt = respondedAt,
            ),
        )
    }

    private fun lesson(owner: User, pairId: Long, date: LocalDate, start: String) {
        em.persist(
            LessonEntity(
                userIsu = owner.isu, date = date, pairId = pairId, subjectId = 42, subjectName = "Synthetic subject",
                teacherIsu = null, teacherFio = null, start = LocalTime.parse(start), end = LocalTime.parse(start).plusMinutes(90),
                type = "Лекция", typeId = 1, groupName = "P3101", flowId = 7000 + pairId, flowTypeId = 2,
                note = null, room = null, building = null, buildingId = null, mainBuildingId = null, format = "Очно", formatId = 1,
            ),
        )
    }

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-09-22")
    }
}
