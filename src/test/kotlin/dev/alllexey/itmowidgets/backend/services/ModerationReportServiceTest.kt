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

class ModerationReportServiceTest {
    private val rows = mock(ModerationReportRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val restrictions = mock(RestrictionService::class.java)
    private val settings = mock(ModerationSettingsService::class.java)
    private val moderation = mock(ModerationService::class.java)
    private val targets = mock(ModerationTargets::class.java)
    private val target = FakeModerationTarget()
    private val reporter = ModerationFixture.user(970002)
    private val id = UUID.randomUUID()
    private val type = ModerationTargetType.SUBJECT_RESOURCE
    private val request = ModerationReportRequest(ReportReason.BROKEN, " Не открывается ")
    private val service = ModerationReportService(rows, users, restrictions, settings, moderation, targets, ModerationFixture.clock)

    @BeforeEach
    fun fixture() {
        `when`(users.lockById(reporter.id)).thenReturn(reporter.id)
        `when`(users.findById(reporter.id)).thenReturn(Optional.of(reporter))
        `when`(settings.policy(type)).thenReturn(ModerationPolicy(reportThreshold = 2, dailyReportLimit = 2))
        `when`(targets.forType(type)).thenReturn(target)
    }

    @Test
    fun `restriction is checked before any reads or writes`() {
        doThrow(RestrictedException(RestrictionCapability.REPORT, null, "Restricted")).`when`(restrictions).require(reporter.id, RestrictionCapability.REPORT)
        assertFailsWith<RestrictedException> { service.report(reporter.id, type, id, request) }
        verifyNoInteractions(rows, users, settings, moderation, targets)
    }

    @Test
    fun `policy quota invalid target and duplicate report write nothing`() {
        `when`(rows.countByReporterIdAndCreatedAtAfter(reporter.id, ModerationFixture.now.minusSeconds(86400))).thenReturn(2)
        assertFailsWith<BusinessRuleException> { service.report(reporter.id, type, id, request) }
        `when`(rows.countByReporterIdAndCreatedAtAfter(reporter.id, ModerationFixture.now.minusSeconds(86400))).thenReturn(0)
        target.reportable = false
        assertFailsWith<BusinessRuleException> { service.report(reporter.id, type, id, request) }
        target.reportable = true
        `when`(rows.existsByTargetAndReporter(type, id, reporter.id)).thenReturn(true)
        assertFailsWith<BusinessRuleException> { service.report(reporter.id, type, id, request) }
        verify(rows, never()).saveAndFlush(any())
        verify(moderation, never()).openCase(type, id, ModerationCaseReason.REPORTS)
    }

    @Test
    fun `configured distinct reporter threshold opens one case and repeat report does not reopen`() {
        var saved: ModerationReportEntity? = null
        doAnswer { it.getArgument<ModerationReportEntity>(0).also { row -> saved = row } }.`when`(rows).saveAndFlush(any())
        `when`(rows.countActiveDistinctReporters(type, id)).thenReturn(2)
        service.report(reporter.id, type, id, request)
        assertEquals("Не открывается", saved?.comment)
        verify(moderation).openCase(type, id, ModerationCaseReason.REPORTS)
        `when`(rows.existsByTargetAndReporter(type, id, reporter.id)).thenReturn(true)
        assertFailsWith<BusinessRuleException> { service.report(reporter.id, type, id, request) }
        verify(moderation, times(1)).openCase(type, id, ModerationCaseReason.REPORTS)
    }

    @Test
    fun `cleanup and dismiss delegate without authorizing read access`() {
        service.dismissAll(type, id)
        service.deleteAllFor(type, id)
        service.activeFor(type, id)
        verify(rows).dismissAll(type, id, ModerationFixture.now)
        verify(rows).deleteAllByTarget(type, id)
        verify(rows).findActive(type, id)
    }
}
