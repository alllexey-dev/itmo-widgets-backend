package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.services.UserRegistrationService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Import(UserRegistrationConcurrencyTest.RegistrationConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRegistrationConcurrencyTest @Autowired constructor(
    private val registration: UserRegistrationService,
    private val jdbc: JdbcTemplate,
    private val gate: RegistrationGate,
) : PostgreSqlRepositoryTest() {
    @AfterEach
    fun removeCommittedSyntheticUsers() {
        gate.barrier = null
        jdbc.update("DELETE FROM users WHERE isu BETWEEN 970001 AND 970010")
    }

    @Test
    fun `concurrent first registrations return one complete identity without orphan settings`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            gate.barrier = CyclicBarrier(2)
            val results = (1..2).map {
                executor.submit<dev.alllexey.itmowidgets.backend.model.User> {
                    registration.findOrCreateByIsu(970001)
                }
            }.map { it.get(15, TimeUnit.SECONDS) }

            // The barrier is reached inside both real transactions before either INSERT.
            assertEquals(2, gate.connectionPids.size)
            assertEquals(1, results.map { it.id }.toSet().size)
            results.forEach {
                assertEquals(it.id, it.settings.userId)
                assertEquals(it.id, it.settings.user.id)
                assertEquals(SharingVisibility.FRIENDS, it.settings.scheduleVisibility)
                assertEquals(SharingVisibility.FRIENDS, it.settings.sportVisibility)
                assertEquals(3, it.settings.autoSignLimit)
            }
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users WHERE isu=970001", Long::class.java))
            assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM user_settings s JOIN users u ON u.id=s.user_id WHERE u.isu=970001",
                Long::class.java,
            ))
            assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM user_settings s LEFT JOIN users u ON u.id=s.user_id WHERE u.id IS NULL",
                Long::class.java,
            ))
        } finally {
            gate.barrier = null
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `repeat registration preserves identity audiences and auto sign quota`() {
        val original = registration.findOrCreateByIsu(970002)
        jdbc.update("UPDATE user_settings SET schedule_visibility='NOBODY', sport_visibility='ALL', auto_sign_limit=7 WHERE user_id=?", original.id)

        val repeated = registration.findOrCreateByIsu(970002)

        assertEquals(original.id, repeated.id)
        assertEquals(original.createdAt, repeated.createdAt)
        assertEquals(original.id, repeated.settings.userId)
        assertEquals(SharingVisibility.NOBODY, repeated.settings.scheduleVisibility)
        assertEquals(SharingVisibility.ALL, repeated.settings.sportVisibility)
        assertEquals(7, repeated.settings.autoSignLimit)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users WHERE isu=970002", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM user_settings WHERE user_id=?", Long::class.java, original.id))
    }

    class RegistrationGate {
        @Volatile var barrier: CyclicBarrier? = null
        val connectionPids: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RegistrationConfig {
        @Bean fun registrationGate() = RegistrationGate()

        @Bean
        fun userRegistrationService(
            repository: UserRepository,
            jdbc: JdbcTemplate,
            gate: RegistrationGate,
        ): UserRegistrationService = UserRegistrationService(object : UserRepository by repository {
            override fun insertIgnore(id: UUID, isu: Int): Int {
                gate.barrier?.let { barrier ->
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                    gate.connectionPids.add(checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                    barrier.await(10, TimeUnit.SECONDS)
                }
                return repository.insertIgnore(id, isu)
            }
        })
    }
}
