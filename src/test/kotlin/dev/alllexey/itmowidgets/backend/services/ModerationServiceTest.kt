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

class ModerationServiceTest {
    private val cases = mock(ModerationCaseRepository::class.java)
    private val decisions = mock(ModerationDecisionRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val access = mock(ModeratorAccess::class.java)
    private val restrictions = mock(RestrictionService::class.java)
    private val targets = mock(ModerationTargets::class.java)
    private val target = FakeModerationTarget()
    private val moderator = ModerationFixture.user(970002)
    private val service = ModerationService(cases, decisions, users, access, restrictions, targets, ModerationFixture.clock)
    private val row = ModerationFixture.case()
    private val saved = mutableListOf<ModerationDecisionEntity>()

    @BeforeEach
    fun fixture() {
        `when`(cases.lockById(row.id)).thenReturn(row.id)
        `when`(cases.findById(row.id)).thenReturn(Optional.of(row))
        `when`(users.findById(moderator.id)).thenReturn(Optional.of(moderator))
        `when`(users.findById(target.owner.id)).thenReturn(Optional.of(target.owner))
        `when`(targets.forType(row.targetType)).thenReturn(target)
        doAnswer { it.getArgument<ModerationDecisionEntity>(0).also(saved::add) }.`when`(decisions).save(any())
        `when`(decisions.findAllByCaseIdOrderByCreatedAt(row.id)).thenAnswer { saved.toList() }
    }

    @Test
    fun `opening existing case is idempotent`() {
        `when`(cases.findOpen(row.targetType, row.targetId)).thenReturn(row)
        assertSame(row, service.openCase(row.targetType, row.targetId, row.reason))
        verify(cases, never()).save(any())
    }

    @Test
    fun `all actions delegate the inserted decision and resolve only terminal actions`() {
        for (action in ModerationAction.entries) {
            row.status = ModerationCaseStatus.OPEN; row.resolvedAt = null
            val request = ModerationDecisionRequest(action, restriction = if (action == ModerationAction.RESTRICT_USER)
                RestrictionRequest(RestrictionCapability.VOTE, 7) else null)
            val response = service.decide(moderator.id, row.id, request)
            assertEquals(action, target.applied.last().first)
            assertSame(saved.last(), target.applied.last().second)
            val terminal = action !in setOf(ModerationAction.RESTRICT_USER, ModerationAction.HIDE_ALL_BY_USER)
            assertEquals(if (terminal) ModerationCaseStatus.RESOLVED else ModerationCaseStatus.OPEN, response.status)
            assertEquals(if (terminal) ModerationFixture.now else null, row.resolvedAt)
        }
        val decision = saved.first { it.action == ModerationAction.RESTRICT_USER }
        verify(restrictions).restrict(decision, target.owner, RestrictionCapability.VOTE, 7, "Community rules violation")
    }

    @Test
    fun `role and open status are required before audit insertion`() {
        doThrow(PermissionDeniedException("Moderator role required")).`when`(access).require(moderator.id)
        assertFailsWith<PermissionDeniedException> { service.decide(moderator.id, row.id, ModerationDecisionRequest(ModerationAction.APPROVE)) }
        verifyNoInteractions(decisions, cases)
        reset(access)
        row.status = ModerationCaseStatus.RESOLVED
        assertFailsWith<BusinessRuleException> { service.decide(moderator.id, row.id, ModerationDecisionRequest(ModerationAction.APPROVE)) }
        assertTrue(saved.isEmpty())
        assertTrue(target.applied.isEmpty())
    }

    @Test
    fun `restriction requires capability and rejects unrelated payload and nonpositive days`() {
        for (request in listOf(ModerationDecisionRequest(ModerationAction.RESTRICT_USER),
            ModerationDecisionRequest(ModerationAction.RESTRICT_USER, restriction = RestrictionRequest(RestrictionCapability.ALL, 0)),
            ModerationDecisionRequest(ModerationAction.APPROVE, restriction = RestrictionRequest(RestrictionCapability.ALL)))) {
            assertFailsWith<InvalidRequestDataException> { service.decide(moderator.id, row.id, request) }
        }
        verifyNoInteractions(decisions, cases)
    }

    @Test
    fun `withdraw closes only an existing open case`() {
        `when`(cases.findOpen(row.targetType, row.targetId)).thenReturn(row)
        service.withdraw(row.targetType, row.targetId)
        assertEquals(ModerationCaseStatus.WITHDRAWN, row.status)
        assertEquals(ModerationFixture.now, row.resolvedAt)
        verify(cases).save(row)
    }
}
