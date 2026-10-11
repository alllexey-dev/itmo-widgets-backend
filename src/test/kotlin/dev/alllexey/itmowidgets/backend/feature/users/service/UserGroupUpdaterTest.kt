package dev.alllexey.itmowidgets.backend.feature.users.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.QualificationEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.FacultyRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.GroupRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.QualificationRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserGroupUpdaterTest {
    private val entityManager = mock(EntityManager::class.java)
    private val groupRepository = mock(GroupRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val group =
        GroupEntity(UUID.randomUUID(), "K3221", 3, QualificationEntity(1, "Synthetic"), FacultyEntity(1, "Synthetic", "SYN"))
    private val user = TestUsers.user(ISU, groups = listOf(group))

    @Test
    fun `an unreadable groups claim keeps the stored groups`() {
        val groupService = GroupService(
            mock(FacultyRepository::class.java),
            mock(QualificationRepository::class.java),
            groupRepository,
            JsonMapper.builder().build(),
            entityManager,
        )
        `when`(userRepository.lockById(user.id)).thenReturn(user.id)
        `when`(entityManager.find(User::class.java, user.id)).thenReturn(user)
        val token = JWT.decode(JWT.create().withClaim("isu", ISU).withClaim("groups", "not a list").sign(Algorithm.none()))

        assertNull(groupService.groupIdsByIdToken(token))
        UserGroupUpdater(groupService, entityManager, groupRepository, userRepository).updateGroups(user, token)

        assertEquals(setOf(group), user.groups)
        verifyNoInteractions(groupRepository)
        verify(entityManager, never()).flush()
    }

    @Test
    fun `a token without groups clears them`() {
        val groupService = mock(GroupService::class.java)
        val token = JWT.decode(JWT.create().withClaim("isu", ISU).sign(Algorithm.none()))
        `when`(userRepository.lockById(user.id)).thenReturn(user.id)
        `when`(entityManager.find(User::class.java, user.id)).thenReturn(user)
        `when`(groupService.groupIdsByIdToken(token)).thenReturn(emptyList())
        `when`(groupRepository.findAllById(emptyList())).thenReturn(emptyList())

        UserGroupUpdater(groupService, entityManager, groupRepository, userRepository).updateGroups(user, token)

        assertEquals(emptySet(), user.groups)
    }

    private companion object {
        const val ISU = 961001
    }
}
