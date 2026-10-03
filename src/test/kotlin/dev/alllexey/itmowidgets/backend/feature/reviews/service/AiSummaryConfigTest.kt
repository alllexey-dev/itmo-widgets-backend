package dev.alllexey.itmowidgets.backend.feature.reviews.service

import java.net.URI
import java.time.Duration
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

class AiSummaryConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Exercise production placeholders without inheriting this machine's environment or JVM properties.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSummaryConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults keep summaries off without a model a proxy or a key`() {
        contextRunner.run { context ->
            val config = context.getBean(AiSummaryConfig::class.java)
            assertEquals(AiSummaryConfig(apiKey = ""), config)
            assertFalse(config.enabled)
            assertEquals("", config.model)
            assertEquals(URI.create("https://generativelanguage.googleapis.com"), config.baseUrl)
            assertEquals("", config.proxyHost)
            assertEquals(3128, config.proxyPort)
            assertEquals(0, config.dailyRequestBudget)
            assertEquals(Duration.ofSeconds(10), config.requestDelay)
            assertEquals(ZoneId.of("America/Los_Angeles"), config.budgetZone)
            assertEquals(60, config.maxInputReviews)
            assertEquals(60_000, config.maxInputChars)
            assertEquals(3, config.maxAttempts)
            assertEquals(0.2, config.temperature)
            assertEquals(2048, config.maxOutputTokens)
            assertEquals(null, config.thinkingBudget)
            assertEquals(Duration.ofSeconds(10), config.connectTimeout)
            assertEquals(Duration.ofSeconds(90), config.requestTimeout)
        }
    }

    @Test
    fun `the environment enables summaries with the probe values`() {
        contextRunner.withPropertyValues(
            "AI_SUMMARY_ENABLED=true", "GEMINI_MODEL=gemini-3.5-flash-lite", "GEMINI_PROXY_HOST=gemini-proxy",
            "AI_SUMMARY_DAILY_BUDGET=400", "AI_SUMMARY_REQUEST_DELAY=6s", "GEMINI_THINKING_BUDGET=0",
        ).run { context ->
            val config = context.getBean(AiSummaryConfig::class.java)
            assertTrue(config.enabled)
            assertEquals("gemini-3.5-flash-lite", config.model)
            assertEquals("gemini-proxy", config.proxyHost)
            assertEquals(400, config.dailyRequestBudget)
            assertEquals(Duration.ofSeconds(6), config.requestDelay)
            assertEquals(0, config.thinkingBudget)
        }
    }

    @Test
    fun `a disabled configuration without a model or a proxy is valid`() {
        val config = AiSummaryConfig(enabled = false, model = "", proxyHost = "", dailyRequestBudget = 0)
        assertFalse(config.enabled)
    }

    @Test
    fun `an enabled configuration requires a model id a proxy and a budget`() {
        val valid = AiSummaryConfig(enabled = true, model = "gemini-3.5-flash-lite", proxyHost = "gemini-proxy", dailyRequestBudget = 400)
        assertTrue(valid.enabled)
        for ((invalid, message) in listOf(
            { valid.copy(model = "") } to "Gemini model must be a model id",
            { valid.copy(model = "Gemini 3") } to "Gemini model must be a model id",
            { valid.copy(proxyHost = "") } to "Gemini proxy host must be set",
            { valid.copy(proxyHost = " ") } to "Gemini proxy host must be set",
            { valid.copy(proxyPort = 0) } to "Gemini proxy port must be between 1 and 65535",
            { valid.copy(dailyRequestBudget = 0) } to "AI summary daily request budget must be positive",
        )) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { invalid() }.message)
        }
    }

    @Test
    fun `limits are checked whether or not summaries are enabled`() {
        val defaults = AiSummaryConfig()
        for ((invalid, message) in listOf(
            { defaults.copy(baseUrl = URI.create("ftp://generativelanguage.googleapis.com")) } to "Gemini base URL must be an http(s) URL",
            { defaults.copy(requestDelay = Duration.ofMillis(-1)) } to "AI summary request delay must not be negative",
            { defaults.copy(requestTimeout = Duration.ZERO) } to "Gemini request timeout must be positive",
            { defaults.copy(maxInputReviews = 2) } to "AI summary input reviews must be between 3 and 200",
            { defaults.copy(maxInputChars = 999) } to "AI summary input characters must be between 1000 and 200000",
            { defaults.copy(maxAttempts = 0) } to "AI summary attempts must be between 1 and 10",
            { defaults.copy(temperature = 2.1) } to "Gemini temperature must be between 0 and 2",
            { defaults.copy(maxOutputTokens = 255) } to "Gemini output tokens must be between 256 and 65536",
            { defaults.copy(thinkingBudget = -1) } to "Gemini thinking budget must not be negative",
        )) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { invalid() }.message)
        }
    }

    @Test
    fun `an enabled configuration without a model fails binding`() {
        contextRunner.withPropertyValues("AI_SUMMARY_ENABLED=true", "GEMINI_PROXY_HOST=gemini-proxy", "AI_SUMMARY_DAILY_BUDGET=400")
            .run { context ->
                val causes = generateSequence(assertNotNull(context.startupFailure)) { it.cause }.take(20).toList()
                assertTrue(causes.any { it is ConfigurationPropertiesBindException }, "Configuration binding must fail closed")
                assertTrue(causes.any { it is IllegalArgumentException && it.message == "Gemini model must be a model id" })
            }
    }

    @Test
    fun `toString never shows the seeded key`() {
        val key = "AIza" + "0".repeat(35)
        val text = AiSummaryConfig(apiKey = key).toString()
        assertFalse(key in text)
        assertTrue("apiKey=redacted" in text)
        assertTrue("apiKey=none" in AiSummaryConfig().toString())
    }
}
