package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.services.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CyclicBarrier
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Import(ModerationService::class, ModerationTargets::class, ModerationReportService::class,
    ModerationSettingsService::class, ModeratorAccess::class, AdminAccess::class, RestrictionService::class,
    CommunityModerationPersistenceTest.TargetConfig::class)
class CommunityModerationPersistenceTest @Autowired constructor(
    private val reports: ModerationReportService,
    private val em: org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager,
    private val cases: ModerationCaseRepository,
    private val reportRows: ModerationReportRepository,
    private val transactions: PlatformTransactionManager,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) : PostgreSqlRepositoryTest() {

    /** Stands in for the link service: an approved revision of another author is reportable. */
    @TestConfiguration(proxyBeanMethods = false)
    class TargetConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun revisionTarget(revisions: SubjectLinkRevisionRepository): ModerationTarget = object : ModerationTarget {
            override fun targetType() = ModerationTargetType.SUBJECT_RESOURCE
            override fun ownerId(targetId: UUID) = revisions.findById(targetId).orElseThrow().link.owner.id
            override fun isReportable(targetId: UUID, reporterId: UUID) = revisions.findById(targetId).orElse(null)
                ?.let { it.status == LinkRevisionStatus.APPROVED && it.link.hiddenAt == null && it.link.owner.id != reporterId } == true
            override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) = Unit
            override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget = throw UnsupportedOperationException()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `simultaneous distinct reports on an approved link revision cross the threshold exactly once`() {
        val tx = TransactionTemplate(transactions)
        val people = tx.execute { (981011..981014).map(::owner) }!!
        val (link, revision) = tx.execute { approvedLink(people.first()) }!!
        val executor = Executors.newFixedThreadPool(3)
        val barrier = CyclicBarrier(3)
        try {
            val tasks = people.drop(1).map { reporter -> executor.submit {
                barrier.await(10, TimeUnit.SECONDS)
                reports.report(reporter.id, ModerationTargetType.SUBJECT_RESOURCE, revision, ModerationReportRequest(ReportReason.BROKEN))
            } }
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            tx.executeWithoutResult {
                assertEquals(3, reportRows.countActiveDistinctReporters(ModerationTargetType.SUBJECT_RESOURCE, revision))
                assertEquals(ModerationCaseReason.REPORTS, cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, revision)?.reason)
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM moderation_cases WHERE target_id = ? AND status = 'OPEN'", Int::class.java, revision))
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            tx.executeWithoutResult {
                jdbc.update("DELETE FROM moderation_reports WHERE target_id = ?", revision)
                jdbc.update("DELETE FROM moderation_cases WHERE target_id = ?", revision)
                jdbc.update("DELETE FROM subject_links WHERE id = ?", link)
                people.forEach { jdbc.update("DELETE FROM users WHERE id = ?", it.id) }
            }
        }
    }

    private fun approvedLink(author: User): Pair<UUID, UUID> {
        val link = em.persist(SubjectLinkEntity(id = UUID.randomUUID(), owner = author, subjectId = 43, subjectName = "Предмет",
            periodKey = "2026-1", category = LinkCategory.MATERIALS, url = "https://github.com/concurrency",
            normalizedUrl = "https://github.com/concurrency", title = "Материалы", visibility = LinkVisibility.ALL,
            createdAt = NOW, updatedAt = NOW))
        val revision = em.persistAndFlush(SubjectLinkRevisionEntity(link = link, number = 1, category = link.category,
            url = link.url, normalizedUrl = link.normalizedUrl, title = link.title, visibility = link.visibility,
            status = LinkRevisionStatus.APPROVED, submittedAt = NOW, decidedAt = NOW))
        return link.id to revision.id
    }

    private fun owner(isu: Int) = em.persistAndFlush(User(isu = isu, name = "Synthetic user", pictureUrl = null, createdAt = NOW).apply {
        settings = UserSettingsEntity(user = this)
    })

    companion object { private val NOW = Instant.parse("2026-09-22T09:00:00Z") }
}
