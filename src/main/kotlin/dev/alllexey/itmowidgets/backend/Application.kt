package dev.alllexey.itmowidgets.backend

import dev.alllexey.itmowidgets.backend.configs.AppConfig
import dev.alllexey.itmowidgets.backend.configs.MyItmoConfig
import dev.alllexey.itmowidgets.backend.configs.RetentionConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppConfig::class, MyItmoConfig::class, RetentionConfig::class)
class Application

fun main(args: Array<String>) {
	runApplication<Application>(*args)
}
