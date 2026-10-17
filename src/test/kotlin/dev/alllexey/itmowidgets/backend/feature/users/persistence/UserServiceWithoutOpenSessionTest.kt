package dev.alllexey.itmowidgets.backend.feature.users.persistence

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.users.service.GroupService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserGroupUpdater
import dev.alllexey.itmowidgets.backend.feature.users.service.UserRegistrationService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Without open-in-view a controller holds the users it loads detached: what it reads from them is fetched inside
 * the repository call, and what a service changes on them is applied to the managed row.
 */
@Import(UserService::class, UserGroupUpdater::class, UserRegistrationService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserServiceWithoutOpenSessionTest @Autowired constructor(private val service: UserService, private val jdbc: JdbcTemplate) :
    PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var itmoJwtVerifier: ItmoJwtVerifier

    @MockitoBean private lateinit var groupService: GroupService

    private lateinit var id: UUID
    private val group = UUID.randomUUID()

    @BeforeEach
    fun user() {
        id = jdbc.insertUser(ISU, "Synthetic before")
        jdbc.update("INSERT INTO qualifications (code, name) VALUES ($ISU, 'Synthetic')")
        jdbc.update("INSERT INTO faculties (id, name, short_name) VALUES ($ISU, 'Synthetic', 'SYN')")
        jdbc.update("INSERT INTO groups (id, name, course, qualification_id, faculty_id) VALUES (?, 'K3221', 3, $ISU, $ISU)", group)
        jdbc.update("INSERT INTO user_groups (user_id, group_id) VALUES (?, ?)", id, group)
    }

    @AfterEach
    fun remove() {
        jdbc.update("DELETE FROM user_groups WHERE user_id = ?", id)
        jdbc.update("DELETE FROM user_settings WHERE user_id = ?", id)
        jdbc.update("DELETE FROM users WHERE id = ?", id)
        jdbc.update("DELETE FROM groups WHERE id = ?", group)
        jdbc.update("DELETE FROM faculties WHERE id = $ISU")
        jdbc.update("DELETE FROM qualifications WHERE code = $ISU")
    }

    @Test
    fun `a loaded user carries its groups outside any transaction`() {
        val groups = service.findUserById(id).groups
        assertEquals(listOf("K3221"), groups.map { it.name })
        // GroupEntity.toDto reads the faculty, as /api/web/auth/me and /api/users/me/data do.
        assertEquals(1, groups.map { it.toDto() }.size)
    }

    @Test
    fun `ID token data reaches the row when the caller holds a detached user`() {
        val token = JWT.decode(
            JWT.create().withClaim("isu", ISU).withClaim("name", "Synthetic after")
                .withClaim("email", "synthetic@example.test").withClaim("gender", "male").sign(Algorithm.none()),
        )
        `when`(itmoJwtVerifier.verifyAndDecode("synthetic-id-token")).thenReturn(token)
        `when`(groupService.groupIdsByIdToken(any() ?: token)).thenReturn(null)

        service.updateDataFromIdToken(service.findUserById(id), "synthetic-id-token")

        assertEquals("Synthetic after", jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String::class.java, id))
        assertEquals("synthetic@example.test", jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String::class.java, id))
        assertEquals("male", jdbc.queryForObject("SELECT gender FROM users WHERE id = ?", String::class.java, id))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_groups WHERE user_id = ?", Int::class.java, id))
    }

    @Test
    fun `the name is family then given name when the ID token has both parts`() {
        val token = JWT.decode(
            JWT.create().withClaim("isu", ISU).withClaim("name", "Тест Синтетический")
                .withClaim("family_name", " Синтетический ").withClaim("given_name", "Тест").sign(Algorithm.none()),
        )
        `when`(itmoJwtVerifier.verifyAndDecode("synthetic-id-token")).thenReturn(token)
        `when`(groupService.groupIdsByIdToken(any() ?: token)).thenReturn(null)

        service.updateDataFromIdToken(service.findUserById(id), "synthetic-id-token")

        val row = jdbc.queryForMap("SELECT name, family_name, given_name FROM users WHERE id = ?", id)
        assertEquals(mapOf("name" to "Синтетический Тест", "family_name" to "Синтетический", "given_name" to "Тест"), row)
    }

    private companion object {
        const val ISU = 961101
    }
}
