package dev.alllexey.itmowidgets.backend.configs

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource

class AppConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration::class.java)
        .withInitializer { context ->
            // Load production placeholders without host environment affecting defaults.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addLast(ResourcePropertySource(ClassPathResource("application.properties")))
        }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppConfig::class)
    class BindingConfiguration

    @Test
    fun `production defaults announce minimum and latest 2_1 with empty note`() {
        contextRunner.run { context ->
            assertEquals(AppConfig("2.1", "2.1", ""), context.getBean(AppConfig::class.java))
        }
    }

    @Test
    fun `environment placeholders bind independent overrides and plain text note`() {
        contextRunner.withPropertyValues(
            "APP_VERSION=2.3",
            "MIN_APP_VERSION=2.2",
            "APP_VERSION_NOTE=Обновление <без HTML> & без Markdown",
        ).run { context ->
            assertEquals(
                AppConfig("2.3", "2.2", "Обновление <без HTML> & без Markdown"),
                context.getBean(AppConfig::class.java),
            )
        }
    }

    @Test
    fun `latest override does not silently move minimum or require a note`() {
        contextRunner.withPropertyValues("APP_VERSION=2.4-SNAPSHOT").run { context ->
            assertEquals(AppConfig("2.4-SNAPSHOT", "2.1", ""), context.getBean(AppConfig::class.java))
        }
    }
}
