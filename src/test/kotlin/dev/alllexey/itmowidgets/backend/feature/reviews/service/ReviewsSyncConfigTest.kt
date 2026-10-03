package dev.alllexey.itmowidgets.backend.feature.reviews.service

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewsSyncConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Exercise production placeholders without inheriting this machine's environment or JVM properties.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewsSyncConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults keep the sync off and pace requests to the public Reviews site`() {
        contextRunner.run { context ->
            assertEquals("false", context.environment.getProperty("itmowidgets.reviews-sync.enabled"))
            assertEquals(
                ReviewsSyncConfig(
                    enabled = false,
                    baseUrl = URI.create("https://reviews.work.gd"),
                    requestDelay = Duration.ofMillis(1500),
                    connectTimeout = Duration.ofSeconds(10),
                    requestTimeout = Duration.ofSeconds(30),
                ),
                context.getBean(ReviewsSyncConfig::class.java),
            )
        }
    }

    @Test
    fun `the environment switch enables the sync without changing its pacing`() {
        contextRunner.withPropertyValues("REVIEWS_SYNC_ENABLED=true").run { context ->
            assertEquals(ReviewsSyncConfig(enabled = true), context.getBean(ReviewsSyncConfig::class.java))
        }
    }

    @Test
    fun `a negative request delay fails configuration binding`() {
        contextRunner.withPropertyValues("REVIEWS_SYNC_REQUEST_DELAY=-1ms").run { context ->
            assertInvalidBinding(context.startupFailure, "Reviews sync request delay must not be negative")
        }
    }

    @Test
    fun `a non http base URL fails configuration binding`() {
        contextRunner.withPropertyValues("REVIEWS_SYNC_BASE_URL=ftp://reviews.work.gd").run { context ->
            assertInvalidBinding(context.startupFailure, "Reviews sync base URL must be an http(s) URL")
        }
    }

    private fun assertInvalidBinding(failure: Throwable?, expectedMessage: String) {
        val causes = generateSequence(assertNotNull(failure)) { it.cause }.take(20).toList()
        assertTrue(causes.any { it is ConfigurationPropertiesBindException }, "Configuration binding must fail closed")
        assertTrue(causes.any { it is IllegalArgumentException && it.message == expectedMessage })
    }
}
