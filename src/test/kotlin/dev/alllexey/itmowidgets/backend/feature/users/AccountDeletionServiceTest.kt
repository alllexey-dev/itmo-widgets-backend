package dev.alllexey.itmowidgets.backend.feature.users

import dev.alllexey.itmowidgets.backend.feature.users.persistence.AccountDeletionWorld
import dev.alllexey.itmowidgets.backend.feature.users.persistence.AccountDeletionWorld.Companion.DELETED_ISU
import dev.alllexey.itmowidgets.backend.feature.users.persistence.AccountDeletionWorld.Companion.runRunbook
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * AccountDeletionService against `docs/ops/account-deletion.sql` on the runbook world: the service runs in a
 * transaction that is rolled back after a snapshot, then the script runs on the same untouched world.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountDeletionServiceTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val transactions: PlatformTransactionManager,
) : PostgreSqlRepositoryTest() {
    private lateinit var world: AccountDeletionWorld
    private lateinit var deletedAt: Instant
    private lateinit var service: AccountDeletionService

    @BeforeEach
    fun createWorld() {
        world = AccountDeletionWorld.create(jdbc)
        // Every timestamp of the world is earlier; both deletions write this time or a later one.
        deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        service = AccountDeletionService(jdbc, Clock.fixed(deletedAt, ZoneOffset.UTC))
    }

    @AfterEach
    fun removeWorld() = world.remove()

    @Test
    fun `the service leaves every table as the runbook does`() {
        val byService = TransactionTemplate(transactions).execute { status ->
            service.delete(DELETED_ISU)
            snapshot().also { status.setRollbackOnly() }
        }!!

        val result = runRunbook(DELETED_ISU)

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(snapshot().keys, byService.keys)
        for ((table, rows) in snapshot()) assertEquals(rows, byService.getValue(table), table)
    }

    @Test
    fun `the service records the deletion against the placeholder`() {
        TransactionTemplate(transactions).executeWithoutResult { service.delete(DELETED_ISU) }

        assertEquals(
            listOf(mapOf("actor_id" to placeholder(), "target" to "user:${-DELETED_ISU}", "created_at" to Timestamp.from(deletedAt))),
            jdbc.queryForList("SELECT actor_id, target, created_at FROM admin_audit WHERE action = '$AUDIT_ACTION'"),
        )
    }

    @Test
    fun `a second call changes nothing`() {
        TransactionTemplate(transactions).executeWithoutResult { service.delete(DELETED_ISU) }
        val before = snapshot()

        TransactionTemplate(transactions).executeWithoutResult { service.delete(DELETED_ISU) }

        assertEquals(before, snapshot())
    }

    @Test
    fun `a non-positive ISU is refused`() {
        assertFailsWith<IllegalArgumentException> { service.delete(-DELETED_ISU) }
    }

    private fun placeholder(): UUID = jdbc.queryForObject("SELECT id FROM users WHERE isu = ?", UUID::class.java, -DELETED_ISU)!!

    /**
     * Every table, rows sorted after normalizing what differs between two deletions by design: the placeholder's
     * random id, the deletion time and the service's own audit row (the script reports into a temp table instead).
     */
    private fun snapshot(): Map<String, List<String>> {
        val placeholder = placeholder()
        return jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1", String::class.java)
            .associateWith { table ->
                jdbc.queryForList("SELECT * FROM $table")
                    .filterNot { table == "admin_audit" && it["action"] == AUDIT_ACTION }
                    .map { row ->
                        row.mapValues { (_, value) ->
                            when {
                                value == placeholder -> "<placeholder>"
                                value is Timestamp && !value.toInstant().isBefore(deletedAt) -> "<deletion time>"
                                else -> value
                            }
                        }.toString()
                    }
                    .sorted()
            }
    }

    private companion object {
        const val AUDIT_ACTION = "ACCOUNT_DELETED"
    }
}
