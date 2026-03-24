package dev.alllexey.itmowidgets.backend.configs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import org.springframework.retry.annotation.EnableRetry

@Configuration
@EnableRetry
class Config(
    private val objectMapper: ObjectMapper,
) {

    @PostConstruct
    fun init() {
        objectMapper.registerModule(KotlinModule.Builder().build())
    }
}