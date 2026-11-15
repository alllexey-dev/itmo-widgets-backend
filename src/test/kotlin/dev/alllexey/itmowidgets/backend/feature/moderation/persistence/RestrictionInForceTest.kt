package dev.alllexey.itmowidgets.backend.feature.moderation.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminUserSearchRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The Kotlin and the JPQL form of a restriction in force agree, at the boundary instants included: a restriction that
 * starts now is in force, one that expires now is not.
 */
class RestrictionInForceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val restrictions: UserRestrictionRepository,
    private val search: AdminUserSearchRepository,
) : PostgreSqlRepositoryTest() {
    @Test
    fun `every query keeps exactly the restrictions the entity says are in force`() {
        val moderator = em.persistUser(973_000)
        val case = em.persist(
            ModerationCaseEntity(
                targetType = ModerationTargetType.SUBJECT_RESOURCE,
                targetId = UUID.randomUUID(),
                reason = ModerationCaseReason.SUBMISSION,
                openedAt = NOW.minusSeconds(3600),
            ),
        )
        val decision = em.persist(
            ModerationDecisionEntity(
                case = case,
                moderator = moderator,
                action = ModerationAction.RESTRICT_USER,
                restrictionCapability = RestrictionCapability.VOTE,
                createdAt = NOW.minusSeconds(3600),
            ),
        )
        val cases = mapOf(
            "starts now" to Term(NOW, null, null, inForce = true),
            "expires now" to Term(NOW.minusSeconds(60), NOW, null, inForce = false),
            "expires a second later" to Term(NOW.minusSeconds(60), NOW.plusSeconds(1), null, inForce = true),
            "starts a second later" to Term(NOW.plusSeconds(1), null, null, inForce = false),
            "revoked" to Term(NOW.minusSeconds(60), null, NOW.minusSeconds(1), inForce = false),
        )
        val rows = cases.entries.mapIndexed { index, (name, term) ->
            val user = em.persistUser(973_101 + index, name = "Synthetic restricted $index")
            name to em.persist(
                UserRestrictionEntity(
                    user = user,
                    capability = RestrictionCapability.VOTE,
                    decision = decision,
                    reason = "Синтетическая причина",
                    startsAt = term.startsAt,
                    expiresAt = term.expiresAt,
                    revokedAt = term.revokedAt,
                    revokedBy = moderator.takeIf { term.revokedAt != null },
                ),
            )
        }.toMap()
        em.flush()
        em.clear()

        val expected = cases.filterValues { it.inForce }.keys
        assertEquals(expected, rows.filterValues { it.inForceAt(NOW) }.keys, "Kotlin")

        val userOf = rows.mapValues { it.value.user.id }
        val inForceUsers = userOf.filterKeys { it in expected }.values.toSet()
        assertEquals(inForceUsers, userOf.values.filter { restrictions.findActive(it, NOW).isNotEmpty() }.toSet(), "findActive")
        assertEquals(inForceUsers, restrictions.countActiveByUsers(userOf.values, NOW).map { it.userId }.toSet(), "countActiveByUsers")
        val page = restrictions.findAdminPage(null, true, NOW, PageRequest.of(0, 20), "%synthetic restricted%", "")
        assertEquals(inForceUsers, page.content.map { it.userId }.toSet(), "findAdminPage")
        assertEquals(expected.size.toLong(), page.totalElements, "findAdminPage count")
        assertEquals(inForceUsers, searched(restricted = true), "admin user search, restricted")
        assertEquals(userOf.values.toSet() - inForceUsers, searched(restricted = false), "admin user search, unrestricted")
    }

    private fun searched(restricted: Boolean): Set<UUID> =
        search.search("%synthetic restricted%", "", null, restricted, NOW, PageRequest.of(0, 20)).content.map { it.id }.toSet()

    private data class Term(val startsAt: Instant, val expiresAt: Instant?, val revokedAt: Instant?, val inForce: Boolean)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T09:00:00Z")
    }
}
