package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebSessionConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Exercise production placeholders without inheriting this machine's environment or JVM properties.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebSessionConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults are thirty idle days, a hundred and eighty days at most, twelve hours for admin routes and one-hour access tokens`() {
        contextRunner.run { context ->
            assertEquals(
                WebSessionConfig(Duration.ofDays(30), Duration.ofDays(180), Duration.ofHours(12), Duration.ofHours(1)),
                context.getBean(WebSessionConfig::class.java),
            )
        }
    }

    @Test
    fun `environment overrides bind independently`() {
        contextRunner.withPropertyValues("WEB_SESSION_IDLE_TIMEOUT=2h").run { context ->
            assertEquals(WebSessionConfig(Duration.ofHours(2), Duration.ofDays(180)), context.getBean(WebSessionConfig::class.java))
        }
        contextRunner.withPropertyValues("WEB_SESSION_MAX_LIFETIME=30d").run { context ->
            assertEquals(WebSessionConfig(Duration.ofDays(30), Duration.ofDays(30)), context.getBean(WebSessionConfig::class.java))
        }
        contextRunner.withPropertyValues("WEB_SESSION_ADMIN_MAX_AGE=1h").run { context ->
            assertEquals(
                WebSessionConfig(Duration.ofDays(30), Duration.ofDays(180), Duration.ofHours(1)),
                context.getBean(WebSessionConfig::class.java),
            )
        }
        contextRunner.withPropertyValues("WEB_SESSION_ACCESS_TTL=15m").run { context ->
            assertEquals(
                WebSessionConfig(Duration.ofDays(30), Duration.ofDays(180), Duration.ofHours(12), Duration.ofMinutes(15)),
                context.getBean(WebSessionConfig::class.java),
            )
        }
    }

    @ParameterizedTest
    @CsvSource(
        "WEB_SESSION_IDLE_TIMEOUT=0s, Web session idle timeout must be positive",
        "WEB_SESSION_MAX_LIFETIME=-1d, Web session max lifetime must be positive",
        "WEB_SESSION_ADMIN_MAX_AGE=0s, Web session admin max age must be positive",
        "WEB_SESSION_ADMIN_MAX_AGE=181d, Web session admin max age must not exceed its max lifetime",
        "WEB_SESSION_ACCESS_TTL=0s, Web session access TTL must be positive",
        "WEB_SESSION_ACCESS_TTL=31d, Web session access TTL must not exceed its idle timeout",
    )
    fun `invalid durations fail configuration binding`(property: String, expectedMessage: String) {
        contextRunner.withPropertyValues(property).run { context ->
            val causes = generateSequence(assertNotNull(context.startupFailure)) { it.cause }.take(20).toList()
            assertTrue(causes.any { it is ConfigurationPropertiesBindException }, "Configuration binding must fail closed")
            assertTrue(causes.any { it is IllegalArgumentException && it.message == expectedMessage })
        }
    }
}
