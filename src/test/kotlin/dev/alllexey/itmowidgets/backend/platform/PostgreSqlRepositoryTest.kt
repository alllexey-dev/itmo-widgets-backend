package dev.alllexey.itmowidgets.backend.platform

import dev.alllexey.itmowidgets.backend.Application
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One disposable real PostgreSQL per test JVM. With Ryuk off (`TESTCONTAINERS_RYUK_DISABLED=true`, needed on colima)
 * the JVM's shutdown hook removes the container on a normal exit; a JVM killed without shutdown hooks leaves it running.
 */
object PostgreSqlTestDatabase {
    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("itmowidgets_test")
            .withUsername("itmowidgets_test")
            .withPassword("test-only-password")
            .also { it.start() }
    }
}

/**
 * The base of every PostgreSQL test: a `@DataJpaTest` slice on [PostgreSqlTestDatabase] with all entities and
 * repositories. Spring caches one context per distinct set of `@Import`s, `@MockitoBean`s and properties, so a new
 * test reuses an existing set where it can instead of adding a context of its own.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PostgreSqlRepositoryTest.PersistenceConfig::class])
abstract class PostgreSqlRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = [Application::class])
    @EnableJpaRepositories(basePackageClasses = [Application::class])
    class PersistenceConfig

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            val postgres = PostgreSqlTestDatabase.container
            registry.add("spring.datasource.hikari.maximum-pool-size") { "3" }
            registry.add("spring.datasource.hikari.minimum-idle") { "0" }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
