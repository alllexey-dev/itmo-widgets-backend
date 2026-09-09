package dev.alllexey.itmowidgets.backend.configs

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource

class RetentionConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Exercise production placeholders without inheriting this machine's environment or JVM properties.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetentionConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults retain ninety days and delete at most one thousand rows per batch`() {
        contextRunner.run { context ->
            assertEquals("90", context.environment.getProperty("itmowidgets.retention.sport-update-log-days"))
            assertEquals("1000", context.environment.getProperty("itmowidgets.retention.batch-size"))
            assertEquals(RetentionConfig(90, 1000), context.getBean(RetentionConfig::class.java))
        }
    }

    @Test
    fun `retention days environment override does not change the default batch size`() {
        contextRunner.withPropertyValues("SPORT_UPDATE_LOG_RETENTION_DAYS=30").run { context ->
            assertEquals(RetentionConfig(30, 1000), context.getBean(RetentionConfig::class.java))
        }
    }

    @Test
    fun `batch size environment override does not change the default retention days`() {
        contextRunner.withPropertyValues("TECHNICAL_LOG_RETENTION_BATCH_SIZE=7").run { context ->
            assertEquals(RetentionConfig(90, 7), context.getBean(RetentionConfig::class.java))
        }
    }

    @Test
    fun `both environment overrides bind independently including the smallest positive values`() {
        contextRunner.withPropertyValues(
            "SPORT_UPDATE_LOG_RETENTION_DAYS=1",
            "TECHNICAL_LOG_RETENTION_BATCH_SIZE=1",
        ).run { context ->
            assertEquals(RetentionConfig(1, 1), context.getBean(RetentionConfig::class.java))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `zero and negative retention days fail configuration binding`(days: Int) {
        contextRunner.withPropertyValues("SPORT_UPDATE_LOG_RETENTION_DAYS=$days").run { context ->
            assertInvalidBinding(context.startupFailure, "Sport update log retention days must be positive")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `zero and negative batch sizes fail configuration binding`(batchSize: Int) {
        contextRunner.withPropertyValues("TECHNICAL_LOG_RETENTION_BATCH_SIZE=$batchSize").run { context ->
            assertInvalidBinding(context.startupFailure, "Technical log retention batch size must be positive")
        }
    }

    private fun assertInvalidBinding(failure: Throwable?, expectedMessage: String) {
        val causes = generateSequence(assertNotNull(failure)) { it.cause }.take(20).toList()
        assertTrue(causes.any { it is ConfigurationPropertiesBindException }, "Configuration binding must fail closed")
        assertTrue(causes.any { it is IllegalArgumentException && it.message == expectedMessage })
    }
}
