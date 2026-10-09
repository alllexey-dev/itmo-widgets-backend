package dev.alllexey.itmowidgets.backend

import dev.alllexey.itmowidgets.backend.feature.app.service.AppConfig
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncConfig
import dev.alllexey.itmowidgets.backend.feature.sport.service.RetentionConfig
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    AppConfig::class,
    MyItmoConfig::class,
    RetentionConfig::class,
    ReviewsSyncConfig::class,
    IsuConfig::class,
    AiSummaryConfig::class,
    WebSessionConfig::class,
)
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
