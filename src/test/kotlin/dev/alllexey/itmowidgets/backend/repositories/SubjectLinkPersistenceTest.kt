package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.domain.Limit

class SubjectLinkPersistenceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val links: SubjectLinkRepository,
    private val revisions: SubjectLinkRevisionRepository,
    private val votes: SubjectLinkVoteRepository,
    private val pins: SubjectLinkPinRepository,
    private val flows: UserSubjectFlowRepository,
) : PostgreSqlRepositoryTest() {
    private val now = Instant.parse("2026-09-22T09:00:00Z")

    @Test
    fun `link revisions resolve the latest and latest approved content and count the owner's daily submissions`() {
        val owner = user(951001)
        val link = link(owner, title = null)
        val first = revision(link, 1, LinkRevisionStatus.APPROVED, url = "https://example.org/first")
        revision(link, 2, LinkRevisionStatus.REJECTED, url = "https://example.org/rejected")
        val third = revision(link, 3, LinkRevisionStatus.APPROVED, url = "https://example.org/third")
        val pending = revision(link, 4, LinkRevisionStatus.PENDING, url = "https://example.org/pending", submittedAt = now.plusSeconds(60))
        val other = link(owner)
        revision(other, 1, LinkRevisionStatus.PENDING, submittedAt = now.minusSeconds(86_400))
        em.flush(); em.clear()

        val stored = links.findById(link.id).orElseThrow()
        assertNull(stored.title)
        assertEquals(LinkCategory.MATERIALS, stored.category)
        assertEquals(LinkVisibility.ALL, stored.visibility)
        assertEquals(pending.id, revisions.findLatest(link.id)?.id)
        assertEquals(third.id, revisions.findLatestApproved(link.id)?.id)
        assertEquals("https://example.org/third", revisions.findLatestApproved(link.id)?.url)
        assertEquals(pending.id, revisions.findPending(link.id)?.id)
        assertNull(revisions.findLatestApproved(other.id))
        assertNull(revisions.findPending(UUID.randomUUID()))
        assertEquals(5, revisions.countByOwnerSince(owner.id, now.minusSeconds(86_400)))
        assertEquals(4, revisions.countByOwnerSince(owner.id, now))
        assertEquals(1, revisions.countByOwnerSince(owner.id, now.plusSeconds(1)))
        assertEquals(listOf(link.id, other.id), links.findOwned(owner.id, 42, "2026-1").map { it.id })
        assertTrue(links.findOwned(owner.id, 42, "2025-2").isEmpty())
        assertEquals(link.id, links.lockById(link.id))
        assertNull(links.lockById(UUID.randomUUID()))
        assertNotNull(first.decidedAt)
    }

    @Test
    fun `votes sum per link and a deleted link takes its revisions votes and pins`() {
        val owner = user(951011)
        val voter = user(951012)
        val link = link(owner)
        revision(link, 1, LinkRevisionStatus.APPROVED)
        em.persist(SubjectLinkVoteEntity(SubjectLinkVoteId(link.id, voter.id), -1, now))
        em.persist(SubjectLinkVoteEntity(SubjectLinkVoteId(link.id, owner.id), 1, now))
        em.persist(SubjectLinkPinEntity(SubjectLinkPinId(voter.id, 42, "2026-1"), link.id))
        em.flush(); em.clear()

        assertEquals(0, votes.sumValues(link.id))
        votes.deleteById(SubjectLinkVoteId(link.id, owner.id)); votes.flush()
        assertEquals(-1, votes.sumValues(link.id))
        assertEquals(0, votes.sumValues(UUID.randomUUID()))
        assertEquals(link.id, pins.findById(SubjectLinkPinId(voter.id, 42, "2026-1")).orElseThrow().linkId)

        links.deleteById(link.id); links.flush(); em.clear()
        assertNull(revisions.findLatest(link.id))
        assertEquals(0, votes.sumValues(link.id))
        assertFalse(pins.existsById(SubjectLinkPinId(voter.id, 42, "2026-1")))
    }

    @Test
    fun `visible candidates are shared not hidden links with approved content`() {
        val owner = user(951021)
        val shared = link(owner, visibility = LinkVisibility.FLOW).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        val public = link(owner).also {
            revision(it, 1, LinkRevisionStatus.APPROVED)
            revision(it, 2, LinkRevisionStatus.PENDING)
        }
        link(owner).also { revision(it, 1, LinkRevisionStatus.PENDING) }
        link(owner).also { revision(it, 1, LinkRevisionStatus.REJECTED) }
        link(owner, visibility = LinkVisibility.PRIVATE).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, hidden = true).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2025-2").also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        em.flush(); em.clear()

        assertEquals(setOf(shared.id, public.id), links.findVisibleCandidates(42, "2026-1").map { it.id }.toSet())
        assertEquals(FLOW_ID, links.findById(shared.id).orElseThrow().flowId)
        assertEquals(FLOW_ID, revisions.findLatestApproved(shared.id)?.flowId)
        assertNull(links.findById(public.id).orElseThrow().flowId)
        assertNull(revisions.findLatestApproved(public.id)?.flowId)
    }

    @Test
    fun `previous links are approved public links of allowed categories from earlier periods by score`() {
        val owner = user(951031)
        val best = link(owner, periodKey = "2025-2", category = LinkCategory.NOTES, score = 9)
        val older = link(owner, periodKey = "2024-1", category = LinkCategory.EXAM, score = 3)
        val low = link(owner, periodKey = "2025-1", category = LinkCategory.MATERIALS, score = -1)
        listOf(best, older, low).forEach { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2025-2", category = LinkCategory.CHAT, score = 20).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2025-2", category = LinkCategory.SCORES, score = 20).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2026-1", category = LinkCategory.NOTES, score = 20).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2026-2", category = LinkCategory.NOTES, score = 20).also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2025-2", category = LinkCategory.NOTES, score = 20).also { revision(it, 1, LinkRevisionStatus.PENDING) }
        link(owner, periodKey = "2025-2", category = LinkCategory.NOTES, score = 20, visibility = LinkVisibility.FLOW)
            .also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, periodKey = "2025-2", category = LinkCategory.NOTES, score = 20, hidden = true)
            .also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        link(owner, subjectId = 43, periodKey = "2025-2", category = LinkCategory.NOTES, score = 20)
            .also { revision(it, 1, LinkRevisionStatus.APPROVED) }
        em.flush(); em.clear()

        assertEquals(listOf(best.id, older.id, low.id),
            links.findPrevious(42, "2026-1", LinkCategory.PREVIOUS_YEARS, Limit.of(10)).map { it.id })
        assertEquals(listOf(best.id, older.id), links.findPrevious(42, "2026-1", LinkCategory.PREVIOUS_YEARS, Limit.of(2)).map { it.id })
        assertEquals(listOf(older.id), links.findPrevious(42, "2025-1", LinkCategory.PREVIOUS_YEARS, Limit.of(10)).map { it.id })
    }

    @Test
    fun `user subject flow upsert refreshes the row and never moves last seen back`() {
        val student = user(951041)
        em.flush()
        flows.upsert(student.id, 42, "2026-1", 7001, "P3119", 2, LocalDate.parse("2026-10-01"))
        flows.upsert(student.id, 42, "2026-1", 7002, "P3119", 1, LocalDate.parse("2026-09-15"))
        flows.upsert(student.id, 42, "2025-2", 6001, "P3119", 2, LocalDate.parse("2026-03-01"))
        flows.upsert(student.id, 42, "2026-1", 7001, "P3120", 3, LocalDate.parse("2026-09-20"))
        em.flush(); em.clear()

        val stored = flows.findByUserAndScope(student.id, 42, "2026-1")
        assertEquals(listOf(7001L, 7002L), stored.map { it.id.flowId })
        assertEquals("P3120", stored[0].groupName)
        assertEquals(3, stored[0].typeId)
        assertEquals(LocalDate.parse("2026-10-01"), stored[0].lastSeen)
        flows.upsert(student.id, 42, "2026-1", 7002, "P3119", 1, LocalDate.parse("2026-11-01"))
        em.clear()
        assertEquals(LocalDate.parse("2026-11-01"), flows.findByUserAndScope(student.id, 42, "2026-1")[1].lastSeen)
        assertEquals(listOf(6001L), flows.findByUserAndScope(student.id, 42, "2025-2").map { it.id.flowId })
        assertTrue(flows.findByUserAndScope(user(951042).id, 42, "2026-1").isEmpty())
    }

    private fun user(isu: Int) = em.persist(User(isu = isu, name = "Synthetic user", pictureUrl = null, createdAt = now).apply {
        settings = UserSettingsEntity(user = this)
    })

    private var created = 0L

    private fun link(
        owner: User,
        subjectId: Long = 42,
        periodKey: String = "2026-1",
        category: LinkCategory = LinkCategory.MATERIALS,
        visibility: LinkVisibility = LinkVisibility.ALL,
        score: Int = 0,
        hidden: Boolean = false,
        title: String? = "Материалы",
    ): SubjectLinkEntity {
        // Distinct creation times keep the owner's list order deterministic.
        val at = now.plusMillis(created++)
        val url = "https://example.org/${UUID.randomUUID()}"
        return em.persist(SubjectLinkEntity(id = UUID.randomUUID(), owner = owner, subjectId = subjectId, subjectName = "Предмет",
            periodKey = periodKey, category = category, url = url, normalizedUrl = url, title = title, visibility = visibility,
            flowId = if (visibility == LinkVisibility.FLOW) FLOW_ID else null, score = score, hiddenAt = if (hidden) now else null, createdAt = at, updatedAt = at))
    }

    private fun revision(
        link: SubjectLinkEntity,
        number: Int,
        status: LinkRevisionStatus,
        url: String = link.url,
        submittedAt: Instant = now,
    ) = em.persist(SubjectLinkRevisionEntity(link = link, number = number, category = link.category, url = url,
        normalizedUrl = url, title = link.title, visibility = link.visibility, flowId = link.flowId, status = status, submittedAt = submittedAt,
        decidedAt = if (status == LinkRevisionStatus.PENDING) null else submittedAt))

    private companion object {
        const val FLOW_ID = 7001L
    }
}
