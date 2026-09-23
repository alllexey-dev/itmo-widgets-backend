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

class RestrictionServiceTest {
    private val rows = mock(UserRestrictionRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val access = mock(ModeratorAccess::class.java)
    private val owner = ModerationFixture.user()
    private val moderator = ModerationFixture.user(970002)
    private val now = ModerationFixture.now
    private val service = RestrictionService(rows, users, access, ModerationFixture.clock)
    private val decision = ModerationDecisionEntity(case = ModerationFixture.case(), moderator = moderator,
        action = ModerationAction.RESTRICT_USER, restrictionCapability = RestrictionCapability.VOTE, createdAt = now)

    @Test
    fun `empty restrictions allow every capability and own capability or ALL denies only mutations`() {
        `when`(rows.findActive(owner.id, now)).thenReturn(emptyList())
        RestrictionCapability.entries.forEach { service.require(owner.id, it) }
        for (capability in listOf(RestrictionCapability.VOTE, RestrictionCapability.ALL)) {
            `when`(rows.findActive(owner.id, now)).thenReturn(listOf(restriction(capability)))
            assertEquals(capability, assertFailsWith<RestrictedException> { service.require(owner.id, RestrictionCapability.VOTE) }.capability)
            assertEquals(1, service.activeFor(owner.id).size)
            if (capability != RestrictionCapability.ALL) service.require(owner.id, RestrictionCapability.REPORT)
        }
    }

    @Test
    fun `expired revoked and future restrictions are inactive`() {
        `when`(rows.findActive(owner.id, now)).thenReturn(listOf(
            restriction(expires = now), restriction(revoked = now), restriction(starts = now.plusSeconds(1))))
        service.require(owner.id, RestrictionCapability.VOTE)
        assertTrue(service.activeFor(owner.id).isEmpty())
    }

    @Test
    fun `permanent restrictions and idempotent moderator revocation`() {
        val saved = mutableListOf<UserRestrictionEntity>()
        doAnswer { it.getArgument<UserRestrictionEntity>(0).also(saved::add) }.`when`(rows).save(any())
        service.restrict(decision, owner, RestrictionCapability.VOTE, null, "Правила")
        assertNull(saved.single().expiresAt)
        assertEquals(now, saved.single().startsAt)
        val row = saved.single()
        `when`(rows.findById(row.id)).thenReturn(Optional.of(row))
        `when`(users.findById(moderator.id)).thenReturn(Optional.of(moderator))
        clearInvocations(rows)
        service.revoke(row.id, moderator.id)
        service.revoke(row.id, moderator.id)
        verify(rows, times(1)).save(row)
        assertEquals(moderator.id, row.revokedBy?.id)
        assertEquals(now, row.revokedAt)
    }

    @Test
    fun `invalid durations and unauthorized revoke write nothing`() {
        assertFailsWith<InvalidRequestDataException> { service.restrict(decision, owner, RestrictionCapability.VOTE, 0, "Правила") }
        doThrow(PermissionDeniedException("Moderator role required")).`when`(access).require(owner.id)
        assertFailsWith<PermissionDeniedException> { service.revoke(UUID.randomUUID(), owner.id) }
        verifyNoInteractions(rows, users)
    }

    private fun restriction(capability: RestrictionCapability = RestrictionCapability.VOTE,
        expires: Instant? = null, revoked: Instant? = null, starts: Instant = now.minusSeconds(1)) =
        UserRestrictionEntity(user = owner, capability = capability, decision = decision, reason = "Правила",
            startsAt = starts, expiresAt = expires, revokedAt = revoked)
}
