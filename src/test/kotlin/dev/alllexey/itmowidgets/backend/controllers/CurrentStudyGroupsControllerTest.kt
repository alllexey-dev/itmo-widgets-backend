package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.services.*
import dev.alllexey.itmowidgets.core.model.GroupData
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFalse

@WebMvcTest(UserController::class, FriendController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, CurrentStudyGroupsService::class,
    CurrentStudyGroupsControllerTest.TimeConfig::class)
class CurrentStudyGroupsControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var users: UserService
    @MockitoBean private lateinit var privacy: UserPrivacyService
    @MockitoBean private lateinit var profiles: UserProfileService
    @MockitoBean private lateinit var source: OfficialStudyGroupsSource
    private val viewerId = UUID.randomUUID()

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC)
    }

    @BeforeEach fun authentication() {
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }
            .`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @Test fun `friends and public profiles return only official current groups with original viewer permissions`() {
        val owner = profile(100101)
        `when`(profiles.friends(viewerId)).thenReturn(listOf(owner))
        `when`(profiles.profile(viewerId, owner.user.isu)).thenReturn(owner)
        `when`(source.load(owner.user.isu)).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            listOf(OfficialStudyGroup("NEW", 2, "Synthetic faculty"))
        }
        mvc.perform(get("/api/friends").with(user(viewerId.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].user.groups.length()").value(1))
            .andExpect(jsonPath("$.data[0].user.groups[0].name").value("NEW"))
            .andExpect(jsonPath("$.data[0].relationship").value("NONE"))
            .andExpect(jsonPath("$.data[0].user.capabilities.canViewSchedule").value(false))
        mvc.perform(get("/api/users/${owner.user.isu}").with(user(viewerId.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.user.groups.length()").value(1))
        verify(source, times(1)).load(owner.user.isu)
    }

    @Test fun `another persons friends resolve member groups not the list owners groups`() {
        val member = profile(100102)
        `when`(profiles.userFriends(viewerId, 200001)).thenReturn(listOf(member))
        `when`(source.load(member.user.isu)).thenReturn(listOf(OfficialStudyGroup("NEW", 2, "Faculty")))
        mvc.perform(get("/api/users/200001/friends").with(user(viewerId.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data[0].user.groups.length()").value(1))
        verify(source).load(member.user.isu)
        verify(source, never()).load(200001)
    }

    @Test fun `anonymous denied and missing profiles never trigger directory reads`() {
        for (path in listOf("/api/friends", "/api/users/200002", "/api/users/200002/friends", "/api/users/me/data")) {
            mvc.perform(get(path)).andExpect(status().isForbidden)
        }
        `when`(profiles.userFriends(viewerId, 200002)).thenThrow(PermissionDeniedException("Private"))
        mvc.perform(get("/api/users/200002/friends").with(user(viewerId.toString())))
            .andExpect(status().isForbidden)
        `when`(profiles.profile(viewerId, 200003)).thenThrow(NotFoundException("Missing"))
        mvc.perform(get("/api/users/200003").with(user(viewerId.toString())))
            .andExpect(status().isNotFound)
        verifyNoInteractions(source)
    }

    private fun profile(isu: Int) = UserProfile(UserData(isu, "Synthetic student", null,
        listOf(GroupData("OLD", 1, "SYN"), GroupData("NEW", 2, "SYN")),
        UserCapabilities(false, false, true)), RelationshipState.NONE)
}
