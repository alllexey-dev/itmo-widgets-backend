package dev.alllexey.itmowidgets.backend.feature.sheets.service

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SheetRulesConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Exercise production placeholders without inheriting this machine's environment or JVM properties.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SheetRulesConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults keep sheet rules off without a socket or a budget`() {
        contextRunner.run { context ->
            assertEquals(SheetRulesConfig(), context.getBean(SheetRulesConfig::class.java))
        }
        with(SheetRulesConfig()) {
            assertEquals(false, enabled)
            assertEquals("", socketPath)
            assertEquals("claude-sonnet-5-5", model)
            assertEquals(0, dailyBudget)
            assertEquals(5, userRequestsPerHour)
            assertEquals(Duration.ofDays(1), staleAfter)
            assertEquals(3, maxAttempts)
            assertEquals(Duration.ofSeconds(120), fingerprintTimeout)
            assertEquals(Duration.ofSeconds(480), analyzeTimeout)
        }
    }

    @Test
    fun `the environment enables sheet rules`() {
        contextRunner.withPropertyValues(
            "SHEET_RULES_ENABLED=true",
            "SHEET_ANALYZER_SOCKET=/run/sheet-analyzer/analyzer.sock",
            "SHEET_RULES_DAILY_BUDGET=40",
            "SHEET_RULES_USER_REQUESTS_PER_HOUR=3",
            "SHEET_RULES_STALE_AFTER=12h",
        ).run { context ->
            val config = context.getBean(SheetRulesConfig::class.java)
            assertTrue(config.enabled)
            assertEquals("/run/sheet-analyzer/analyzer.sock", config.socketPath)
            assertEquals(40, config.dailyBudget)
            assertEquals(3, config.userRequestsPerHour)
            assertEquals(Duration.ofHours(12), config.staleAfter)
        }
    }

    @Test
    fun `an enabled configuration requires an absolute socket path and a budget`() {
        val valid = SheetRulesConfig(enabled = true, socketPath = "/run/analyzer.sock", dailyBudget = 40)
        assertTrue(valid.enabled)
        for ((invalid, message) in listOf(
            { valid.copy(socketPath = "") } to "Sheet analyzer socket path must be absolute",
            { valid.copy(socketPath = "run/analyzer.sock") } to "Sheet analyzer socket path must be absolute",
            { valid.copy(dailyBudget = 0) } to "Sheet rules daily budget must be between 1 and 10000",
            { valid.copy(dailyBudget = 10_001) } to "Sheet rules daily budget must be between 1 and 10000",
        )) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { invalid() }.message)
        }
    }

    @Test
    fun `limits are checked whether or not sheet rules are enabled`() {
        val defaults = SheetRulesConfig()
        for ((invalid, message) in listOf(
            { defaults.copy(model = "Claude 5") } to "Sheet rules model must be a model id",
            { defaults.copy(userRequestsPerHour = 0) } to "Sheet rules requests per user and hour must be between 1 and 1000",
            { defaults.copy(staleAfter = Duration.ofSeconds(59)) } to "Sheet rules staleness must be at least a minute",
            { defaults.copy(maxAttempts = 11) } to "Sheet rules attempts must be between 1 and 10",
            { defaults.copy(fingerprintTimeout = Duration.ZERO) } to
                "Sheet analyzer fingerprint timeout must be positive and at most 5 minutes",
            { defaults.copy(analyzeTimeout = Duration.ofSeconds(420)) } to
                "Sheet analyzer analyze timeout must be above 420 s and at most 20 minutes",
            { defaults.copy(analyzeTimeout = Duration.ofMinutes(21)) } to
                "Sheet analyzer analyze timeout must be above 420 s and at most 20 minutes",
        )) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { invalid() }.message)
        }
    }

    @Test
    fun `an enabled configuration without a socket fails binding`() {
        contextRunner.withPropertyValues("SHEET_RULES_ENABLED=true", "SHEET_RULES_DAILY_BUDGET=40").run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is ConfigurationPropertiesBindException })
        }
    }
}
