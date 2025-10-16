package me.alllexey123.itmowidgets.backend

import me.alllexey123.itmowidgets.backend.configs.JwtConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(JwtConfig::class)
class Application

fun main(args: Array<String>) {
	runApplication<Application>(*args)
}
