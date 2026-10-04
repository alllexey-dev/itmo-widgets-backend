package dev.alllexey.itmowidgets.backend.feature.app.service

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource
import kotlin.test.assertEquals

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
            assertEquals(AppConfig("2.1", "2.1", "", IOS_DEFAULTS), context.getBean(AppConfig::class.java))
        }
    }

    @Test
    fun `ios defaults to 2_3 so no ios build is told to update before the keys are set`() {
        contextRunner.run { context ->
            assertEquals(IOS_DEFAULTS, context.getBean(AppConfig::class.java).ios)
        }
    }

    @Test
    fun `ios environment placeholders bind independently of android`() {
        contextRunner.withPropertyValues(
            "IOS_APP_VERSION=2.4",
            "IOS_MIN_APP_VERSION=2.3.1",
            "IOS_APP_VERSION_NOTE=Новая версия для iOS",
        ).run { context ->
            assertEquals(
                AppConfig("2.1", "2.1", "", AppConfig.Platform("2.4", "2.3.1", "Новая версия для iOS")),
                context.getBean(AppConfig::class.java),
            )
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
                AppConfig("2.3", "2.2", "Обновление <без HTML> & без Markdown", IOS_DEFAULTS),
                context.getBean(AppConfig::class.java),
            )
        }
    }

    @Test
    fun `latest override does not silently move minimum or require a note`() {
        contextRunner.withPropertyValues("APP_VERSION=2.4-SNAPSHOT").run { context ->
            assertEquals(AppConfig("2.4-SNAPSHOT", "2.1", "", IOS_DEFAULTS), context.getBean(AppConfig::class.java))
        }
    }

    private companion object {
        val IOS_DEFAULTS = AppConfig.Platform("2.3", "2.3", "")
    }
}
