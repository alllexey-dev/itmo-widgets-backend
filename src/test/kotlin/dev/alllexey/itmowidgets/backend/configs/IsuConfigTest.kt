package dev.alllexey.itmowidgets.backend.configs

import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource

class IsuConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Exercise production placeholders without inheriting this machine's environment or JVM properties.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IsuConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults point at ISU and ITMO ID without a seeded cookie`() {
        contextRunner.run { context ->
            val config = context.getBean(IsuConfig::class.java)
            assertEquals(IsuConfig(
                keycloakIdentity = "",
                baseUrl = URI.create("https://isu.ifmo.ru"),
                identityUrl = URI.create("https://id.itmo.ru/auth/realms/itmo/"),
                requestDelay = Duration.ofSeconds(2),
                connectTimeout = Duration.ofSeconds(10),
                requestTimeout = Duration.ofSeconds(30),
                userAgent = IsuConfig.BROWSER_USER_AGENT,
            ), config)
            assertTrue(config.keycloakIdentity.isNullOrEmpty())
        }
    }

    @Test
    fun `the environment seeds the identity cookie`() {
        contextRunner.withPropertyValues("ISU_KEYCLOAK_IDENTITY=synthetic-identity-value").run { context ->
            val config = context.getBean(IsuConfig::class.java)
            assertEquals("synthetic-identity-value", config.keycloakIdentity)
            assertTrue("synthetic-identity-value" !in config.toString())
        }
    }

    @Test
    fun `a negative request delay fails configuration binding`() {
        contextRunner.withPropertyValues("itmowidgets.isu.request-delay=-1ms").run { context ->
            assertInvalidBinding(context.startupFailure, "ISU request delay must not be negative")
        }
    }

    @Test
    fun `a non http base URL fails configuration binding`() {
        contextRunner.withPropertyValues("itmowidgets.isu.base-url=ftp://isu.ifmo.ru").run { context ->
            assertInvalidBinding(context.startupFailure, "ISU base URL must be an http(s) URL")
        }
    }

    private fun assertInvalidBinding(failure: Throwable?, expectedMessage: String) {
        val causes = generateSequence(assertNotNull(failure)) { it.cause }.take(20).toList()
        assertTrue(causes.any { it is ConfigurationPropertiesBindException }, "Configuration binding must fail closed")
        assertTrue(causes.any { it is IllegalArgumentException && it.message == expectedMessage })
    }
}
