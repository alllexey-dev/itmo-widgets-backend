package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/** One disposable real PostgreSQL per test JVM. Ryuk removes it when the JVM exits. */
object PostgreSqlTestDatabase {
    val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("itmowidgets_test")
            .withUsername("itmowidgets_test")
            .withPassword("test-only-password")
            .also { it.start() }
    }
}

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PostgreSqlRepositoryTest.PersistenceConfig::class])
abstract class PostgreSqlRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = [User::class])
    @EnableJpaRepositories(basePackageClasses = [UserRepository::class])
    class PersistenceConfig

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            val postgres = PostgreSqlTestDatabase.container
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
