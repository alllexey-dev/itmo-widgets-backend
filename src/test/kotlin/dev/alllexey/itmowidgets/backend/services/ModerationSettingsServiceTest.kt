package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.exceptions.*
import dev.alllexey.itmowidgets.backend.repositories.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.Optional
import kotlin.test.*

class ModerationSettingsServiceTest {
    private val repository = mock(ModerationSettingRepository::class.java)
    private val cases = mock(ModerationCaseRepository::class.java)
    private val moderation = mock(ModerationService::class.java)
    private val access = mock(ModeratorAccess::class.java)
    private val moderator = UUID.randomUUID()
    private val type = ModerationTargetType.SUBJECT_RESOURCE
    private val stored = mutableMapOf<String, String>()
    private val targets = mock(ModerationTargets::class.java)
    private val service = ModerationSettingsService(repository, cases, moderation, access, ModerationFixture.clock, targets)

    @BeforeEach
    fun fixture() {
        `when`(targets.forType(type)).thenReturn(FakeModerationTarget())
        `when`(repository.findAllByKeyStartingWith("SUBJECT_RESOURCE.")).thenAnswer {
            stored.map { (key, value) -> ModerationSettingEntity(key, value, ModerationFixture.now, moderator) }
        }
        doAnswer { stored[it.getArgument(0)] = it.getArgument(1); null }.`when`(repository).upsert(anyString(), anyString(), any(Instant::class.java) ?: ModerationFixture.now, any(UUID::class.java) ?: moderator)
    }

    @Test
    fun `disabling premoderation approves only submission queue as the acting moderator`() {
        val submission = ModerationFixture.case()
        val reports = ModerationFixture.case(ModerationCaseReason.REPORTS)
        val votes = ModerationFixture.case(ModerationCaseReason.VOTES)
        `when`(cases.findAllByStatusOrderByOpenedAt(ModerationCaseStatus.OPEN)).thenReturn(listOf(submission, reports, votes))
        assertTrue(service.policy(type).premoderation)
        val request = ModerationSettings(mapOf(type to ModerationPolicy(premoderation = false)))
        assertEquals(request, service.update(moderator, request))
        verify(moderation).decide(moderator, submission.id, ModerationDecisionRequest(ModerationAction.APPROVE, "Premoderation disabled"))
        verify(moderation, times(1)).lock(type)
        assertFalse(service.policy(type).premoderation)
        assertEquals(mapOf("SUBJECT_RESOURCE.premoderation" to "false"), stored)
        clearInvocations(repository, moderation, cases)
        service.update(moderator, request)
        verify(repository, never()).upsert(anyString(), anyString(), any(Instant::class.java) ?: ModerationFixture.now, any(UUID::class.java) ?: moderator)
        verifyNoInteractions(cases)
        verify(moderation, never()).decide(any(UUID::class.java) ?: moderator, any(UUID::class.java) ?: moderator, any(ModerationDecisionRequest::class.java) ?: ModerationDecisionRequest(ModerationAction.APPROVE))
        service.update(moderator, ModerationSettings(mapOf(type to ModerationPolicy())))
        verifyNoInteractions(cases)
        verify(moderation, never()).decide(any(UUID::class.java) ?: moderator, any(UUID::class.java) ?: moderator, any(ModerationDecisionRequest::class.java) ?: ModerationDecisionRequest(ModerationAction.APPROVE))
    }

    @Test
    fun `default policy caches for thirty seconds and missing target or role never writes`() {
        assertEquals(ModerationPolicy(), service.policy(type))
        assertEquals(ModerationPolicy(), service.policy(type))
        verify(repository, times(1)).findAllByKeyStartingWith("SUBJECT_RESOURCE.")
        assertFailsWith<InvalidRequestDataException> { service.update(moderator, ModerationSettings(emptyMap())) }
        doThrow(PermissionDeniedException("Moderator role required")).`when`(access).require(moderator)
        assertFailsWith<PermissionDeniedException> { service.update(moderator, ModerationSettings(mapOf(type to ModerationPolicy()))) }
        verify(repository, never()).upsert(anyString(), anyString(), any(Instant::class.java) ?: ModerationFixture.now, any(UUID::class.java) ?: moderator)
        verifyNoInteractions(cases)
        verify(moderation, never()).decide(any(UUID::class.java) ?: moderator, any(UUID::class.java) ?: moderator, any(ModerationDecisionRequest::class.java) ?: ModerationDecisionRequest(ModerationAction.APPROVE))
    }
}
