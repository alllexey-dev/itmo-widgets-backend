package dev.alllexey.itmowidgets.backend.feature.sheets.service

import dev.alllexey.itmowidgets.backend.contract.ContractSamples
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.service.SharedLinkContent
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.sheets.web.SheetRuleResponse
import dev.alllexey.itmowidgets.backend.feature.sheets.web.SheetRuleState
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The store commits on its own, so this class runs without a test transaction and cleans up after itself. */
@Import(SheetRuleService::class, SheetRuleStore::class, SheetRuleJob::class, SheetRuleServiceTest.TestConfig::class)
@TestPropertySource(
    properties = [
        "itmowidgets.sheet-rules.enabled=true", "itmowidgets.sheet-rules.socket-path=/tmp/unused.sock",
        "itmowidgets.sheet-rules.daily-budget=3", "itmowidgets.sheet-rules.user-requests-per-hour=5",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SheetRuleServiceTest @Autowired constructor(
    private val service: SheetRuleService,
    private val store: SheetRuleStore,
    private val job: SheetRuleJob,
    private val analyzer: FakeAnalyzer,
    private val clock: MutableClock,
    private val config: SheetRulesConfig,
    private val json: JsonMapper,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var links: SubjectLinkService

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SheetRulesConfig::class)
    class TestConfig {
        @Bean fun fakeAnalyzer() = FakeAnalyzer()

        @Bean fun sheetRuleExecutor(): TaskExecutor = SyncTaskExecutor()

        @Bean fun clock() = MutableClock()

        @Bean fun jsonMapper(): JsonMapper = jacksonMapperBuilder().build()
    }

    /** Answers each operation with its current reply; records every call. */
    class FakeAnalyzer : SheetAnalyzerClient {
        val calls = CopyOnWriteArrayList<Pair<AnalyzerOp, String>>()

        @Volatile var fingerprint: () -> AnalyzerResult = { AnalyzerResult.Ok(FP1, null) }

        @Volatile var analyze: () -> AnalyzerResult = { AnalyzerResult.Ok(FP1, ContractSamples.sheetRule) }

        override fun call(op: AnalyzerOp, sheetId: String): AnalyzerResult {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Analyzer call inside a transaction")
            calls.add(op to sheetId)
            return if (op == AnalyzerOp.FINGERPRINT) fingerprint() else analyze()
        }

        fun ops() = calls.map { it.first }
    }

    private val viewer = UUID.randomUUID()
    private val link = UUID.randomUUID()

    @BeforeEach
    fun fixture() {
        cleanUp()
        clock.now = NOW
        analyzer.calls.clear()
        analyzer.fingerprint = { AnalyzerResult.Ok(FP1, null) }
        analyzer.analyze = { AnalyzerResult.Ok(FP1, ContractSamples.sheetRule) }
        shared(link, SHEET)
        `when`(links.isShared(anyString())).thenReturn(true)
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM sheet_rules")
        jdbc.update("UPDATE sheet_rule_state SET running_since = NULL, budget_day = NULL, budget_used = 0")
    }

    @Test
    fun `a first request checks the sharing and the fingerprint then analyzes once`() {
        assertEquals(SheetRuleResponse(SheetRuleState.NONE, null, null, null), service.rule(viewer, link))

        val response = service.request(viewer, link)

        assertEquals(SheetRuleResponse(SheetRuleState.READY, ContractSamples.sheetRule, NOW, null), response)
        assertEquals(response, service.rule(viewer, link))
        assertEquals(listOf(AnalyzerOp.FINGERPRINT to SHEET, AnalyzerOp.ANALYZE to SHEET), analyzer.calls)
        assertEquals(listOf<Any?>("READY", FP1, "claude-sonnet-5-5", 0, true), row())
        assertEquals(1, budgetUsed())
        assertNull(store.find(SHEET)?.lastError)
    }

    @Test
    fun `a fresh verdict is served without the analyzer and without the user limit`() {
        service.request(viewer, link)
        analyzer.calls.clear()
        clock.now = NOW.plus(Duration.ofHours(23))

        repeat(10) { assertEquals(SheetRuleState.READY, service.request(viewer, link).status) }

        assertEquals(emptyList(), analyzer.calls)
    }

    @Test
    fun `a stale verdict with the same structure is confirmed by the fingerprint alone`() {
        service.request(viewer, link)
        analyzer.calls.clear()
        clock.now = NOW.plus(Duration.ofHours(25))

        val response = service.request(viewer, link)

        assertEquals(SheetRuleResponse(SheetRuleState.READY, ContractSamples.sheetRule, NOW, null), response)
        assertEquals(listOf(AnalyzerOp.FINGERPRINT), analyzer.ops())
        assertEquals(1, budgetUsed())
        assertEquals(clock.now, store.find(SHEET)?.checkedAt)
    }

    @Test
    fun `a changed structure drops the old rule and asks for a new one`() {
        service.request(viewer, link)
        analyzer.calls.clear()
        clock.now = NOW.plus(Duration.ofHours(25))
        val changed = ContractSamples.sheetRule.deepCopy().also { (it as ObjectNode).put("summary", "Новая") }
        analyzer.fingerprint = { AnalyzerResult.Ok(FP2, null) }
        analyzer.analyze = { AnalyzerResult.Ok(FP2, changed) }

        assertEquals(SheetRuleResponse(SheetRuleState.READY, changed, clock.now, null), service.request(viewer, link))
        assertEquals(listOf(AnalyzerOp.FINGERPRINT, AnalyzerOp.ANALYZE), analyzer.ops())
        assertEquals(FP2, store.find(SHEET)?.fingerprint)

        clock.now = clock.now.plus(Duration.ofHours(25))
        analyzer.fingerprint = { AnalyzerResult.Ok(FP1, null) }
        analyzer.analyze = { AnalyzerResult.Failure(AnalyzerStatus.MODEL_FAILED) }
        assertEquals(SheetRuleResponse(SheetRuleState.PENDING, null, null, "model_failed"), service.request(viewer, link))
        assertEquals(listOf<Any?>("PENDING", null, null, 1, false), row(), "The rule of the old structure is gone")
    }

    @Test
    fun `a failed check of a stored verdict keeps it and spends nothing`() {
        service.request(viewer, link)
        clock.now = NOW.plus(Duration.ofHours(25))
        analyzer.fingerprint = { AnalyzerResult.Failure(AnalyzerStatus.NETWORK) }

        assertEquals(SheetRuleResponse(SheetRuleState.READY, ContractSamples.sheetRule, NOW, null), service.request(viewer, link))
        assertEquals(listOf<Any?>("READY", FP1, "claude-sonnet-5-5", 0, true), row())
        assertEquals("network", store.find(SHEET)?.lastError)
        assertEquals(1, budgetUsed())
    }

    @Test
    fun `failed attempts are retried by later runs up to the limit and a day later again`() {
        analyzer.analyze = { AnalyzerResult.Failure(AnalyzerStatus.NETWORK) }

        assertEquals(SheetRuleResponse(SheetRuleState.PENDING, null, null, "network"), service.request(viewer, link))
        assertEquals(listOf(AnalyzerOp.FINGERPRINT, AnalyzerOp.ANALYZE), analyzer.ops(), "Once per run")
        tickAfter(Duration.ofMinutes(1))
        assertEquals(listOf<Any?>("PENDING", null, null, 2, false), row())
        tickAfter(Duration.ofMinutes(1))
        assertEquals(SheetRuleResponse(SheetRuleState.FAILED, null, null, "network"), service.rule(viewer, link))
        assertEquals(3, store.find(SHEET)?.attempts)
        tickAfter(Duration.ofMinutes(1))
        assertEquals(6, analyzer.calls.size, "A failed sheet is not retried by the tick")

        assertEquals(SheetRuleState.FAILED, service.request(viewer, link).status, "Not again the same day")
        analyzer.analyze = { AnalyzerResult.Ok(FP1, ContractSamples.sheetRule) }
        clock.now = clock.now.plus(Duration.ofHours(25))
        assertEquals(SheetRuleState.READY, service.request(viewer, link).status)
    }

    @Test
    fun `a closed sheet fails at once without spending the budget`() {
        analyzer.fingerprint = { AnalyzerResult.Failure(AnalyzerStatus.CLOSED) }

        assertEquals(SheetRuleResponse(SheetRuleState.FAILED, null, null, "closed"), service.request(viewer, link))
        assertEquals(listOf(AnalyzerOp.FINGERPRINT), analyzer.ops())
        assertEquals(0, budgetUsed())
    }

    @Test
    fun `an insufficient workbook keeps its fingerprint and an unchanged one stays insufficient`() {
        analyzer.analyze = { AnalyzerResult.Failure(AnalyzerStatus.INSUFFICIENT) }

        assertEquals(SheetRuleResponse(SheetRuleState.INSUFFICIENT, null, NOW, "insufficient"), service.request(viewer, link))
        assertEquals(listOf<Any?>("INSUFFICIENT", FP1, "claude-sonnet-5-5", 0, false), row())
        analyzer.calls.clear()
        clock.now = NOW.plus(Duration.ofHours(25))

        assertEquals(SheetRuleResponse(SheetRuleState.INSUFFICIENT, null, NOW, "insufficient"), service.request(viewer, link))
        assertEquals(listOf(AnalyzerOp.FINGERPRINT), analyzer.ops())
    }

    @Test
    fun `an invalid rule costs an attempt and is never stored`() {
        analyzer.analyze = {
            AnalyzerResult.Ok(FP1, ContractSamples.sheetRule.deepCopy().also { (it as ObjectNode).put("examples", "B4") })
        }

        assertEquals(SheetRuleResponse(SheetRuleState.PENDING, null, null, "invalid_rule"), service.request(viewer, link))
        assertEquals(listOf<Any?>("PENDING", null, null, 1, false), row())
    }

    @Test
    fun `a busy analyzer defers the sheet without an attempt and the tick resumes it`() {
        analyzer.fingerprint = { AnalyzerResult.Failure(AnalyzerStatus.BUSY) }
        assertEquals(SheetRuleResponse(SheetRuleState.PENDING, null, null, "busy"), service.request(viewer, link))
        assertEquals(0, store.find(SHEET)?.attempts)

        analyzer.fingerprint = { AnalyzerResult.Ok(FP1, null) }
        tickAfter(Duration.ofMinutes(1))
        assertEquals(SheetRuleState.READY, service.rule(viewer, link).status)
    }

    @Test
    fun `the day budget holds analyses until the next Moscow day`() {
        val sheets = (1..4).map { index -> UUID.randomUUID().also { shared(it, "sheet-$index") } }
        sheets.take(3).forEach { assertEquals(SheetRuleState.READY, service.request(viewer, it).status) }

        assertEquals(SheetRuleResponse(SheetRuleState.PENDING, null, null, "budget_exhausted"), service.request(viewer, sheets[3]))
        assertEquals(6, analyzer.calls.size, "Nothing asked without budget")
        // 2026-09-24T21:00Z is midnight in Moscow.
        clock.now = Instant.parse("2026-09-24T21:00:00Z")
        job.tick()
        assertEquals(SheetRuleState.READY, service.rule(viewer, sheets[3]).status)
        assertEquals(1, budgetUsed())
    }

    @Test
    fun `the per-user limit counts only requests that queue an analysis`() {
        val sheets = (1..6).map { index -> UUID.randomUUID().also { shared(it, "limit-$index") } }
        analyzer.fingerprint = { AnalyzerResult.Failure(AnalyzerStatus.CLOSED) }
        sheets.take(5).forEach { service.request(viewer, it) }
        repeat(3) { service.request(viewer, sheets[0]) }

        assertFailsWith<TooManyRequestsException> { service.request(viewer, sheets[5]) }
        val other = UUID.randomUUID()
        `when`(links.sharedContent(other, sheets[5])).thenReturn(SharedLinkContent(LinkVisibility.ALL, LINK_URL_PREFIX + "limit-6"))
        assertEquals(SheetRuleState.FAILED, service.request(other, sheets[5]).status, "Another user is not limited")
    }

    @Test
    fun `a sheet that stopped being shared never reaches the analyzer`() {
        `when`(links.isShared(LINK_URL_PREFIX + SHEET)).thenReturn(false)

        assertEquals(SheetRuleResponse(SheetRuleState.FAILED, null, null, "not_shared"), service.request(viewer, link))
        assertEquals(emptyList(), analyzer.calls)
    }

    @Test
    fun `links the caller does not see are not found and other links conflict`() {
        val hidden = UUID.randomUUID()
        val privateLink = UUID.randomUUID().also {
            `when`(links.sharedContent(viewer, it)).thenReturn(
                SharedLinkContent(
                    LinkVisibility.PRIVATE,
                    LINK_URL_PREFIX + SHEET,
                ),
            )
        }
        val other = UUID.randomUUID().also {
            `when`(links.sharedContent(viewer, it)).thenReturn(SharedLinkContent(LinkVisibility.ALL, "https://example.org/scores"))
        }
        for (id in listOf(hidden, privateLink)) {
            assertFailsWith<NotFoundException> { service.rule(viewer, id) }
            assertFailsWith<NotFoundException> { service.request(viewer, id) }
        }
        assertEquals(SheetRuleService.NOT_A_SHEET, assertFailsWith<BusinessRuleException> { service.request(viewer, other) }.message)
        assertEquals(SheetRuleService.NOT_A_SHEET, assertFailsWith<BusinessRuleException> { service.rule(viewer, other) }.message)
        assertEquals(emptyList(), analyzer.calls)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sheet_rules", Int::class.java))
    }

    @Test
    fun `a disabled feature refuses requests before anything else`() {
        val disabled = SheetRuleService(links, store, job, SheetRulesConfig(), json, clock)
        assertEquals(SheetRuleService.DISABLED, assertFailsWith<BusinessRuleException> { disabled.request(viewer, link) }.message)
        assertEquals(SheetRuleState.NONE, disabled.rule(viewer, link).status)
        assertEquals(emptyList(), analyzer.calls)
    }

    @Test
    fun `a held lease waits, a stale one is taken over and startup recovers a crash`() {
        jdbc.update("UPDATE sheet_rule_state SET running_since = ?", Timestamp.from(NOW))
        assertEquals(SheetRuleResponse(SheetRuleState.PENDING, null, null, null), service.request(viewer, link))
        assertEquals(emptyList(), analyzer.calls)
        tickAfter(SheetRuleStore.STALE_LEASE.plusSeconds(1))
        assertEquals(SheetRuleState.READY, service.rule(viewer, link).status)

        clock.now = clock.now.plus(Duration.ofDays(2))
        jdbc.update("UPDATE sheet_rules SET status = 'RUNNING'")
        jdbc.update("UPDATE sheet_rule_state SET running_since = ?", Timestamp.from(clock.now))
        job.onApplicationEvent(mock(ApplicationReadyEvent::class.java))
        assertEquals(null, jdbc.queryForObject("SELECT running_since FROM sheet_rule_state", Any::class.java))
        assertEquals(SheetRuleState.PENDING, service.rule(viewer, link).status)
        analyzer.calls.clear()
        tickAfter(Duration.ofMinutes(1))
        assertEquals(listOf(AnalyzerOp.FINGERPRINT), analyzer.ops(), "The verdict survives the crash")
        assertEquals(SheetRuleState.READY, service.rule(viewer, link).status)
    }

    private fun shared(linkId: UUID, sheetId: String) {
        `when`(links.sharedContent(viewer, linkId)).thenReturn(SharedLinkContent(LinkVisibility.FLOW, LINK_URL_PREFIX + sheetId))
    }

    private fun tickAfter(delay: Duration) {
        clock.now = clock.now.plus(delay)
        job.tick()
    }

    /** Status, fingerprint, model, attempts and whether a rule is stored. */
    private fun row(): List<Any?> = jdbc.queryForList(
        "SELECT status, fingerprint, model, attempts, rule IS NOT NULL AS has_rule FROM sheet_rules WHERE sheet_id = ?",
        SHEET,
    ).single().values.toList()

    private fun budgetUsed(): Int = jdbc.queryForObject("SELECT budget_used FROM sheet_rule_state", Int::class.java)!!

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        const val SHEET = "1SyntheticSheet_-id"
        const val LINK_URL_PREFIX = "https://docs.google.com/spreadsheets/d/"
        val FP1 = "1".repeat(64)
        val FP2 = "2".repeat(64)
    }
}
